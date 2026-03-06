package com.example.sinkconnect.domain.logic.alert.service;

import com.example.sinkconnect.domain.common.model.PriceEventMessage;
import com.example.sinkconnect.domain.logic.alert.actor.AlertManagerActor;
import com.example.sinkconnect.infrastructure.entity.PriceAlertEntity;
import com.example.sinkconnect.infrastructure.repository.PriceAlertRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.kafka.ConsumerMessage;
import org.apache.pekko.kafka.ConsumerSettings;
import org.apache.pekko.kafka.Subscriptions;
import org.apache.pekko.kafka.javadsl.Consumer;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.RestartSettings;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.javadsl.RestartSource;
import org.apache.pekko.stream.javadsl.Sink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Chart1mConsumerService - Akka Streams Kafka Consumer with Backpressure
 * <p>
 * Responsibilities:
 * 1. Consume chart1m-data topic using Akka Streams
 * 2. Implement backpressure to prevent actor mailbox overflow
 * 3. Group messages by (source, symbol)
 * 4. Fetch active alerts for each symbol from ScyllaDB
 * 5. Forward CheckPrice commands to AlertManagerActor
 * 6. Track previous price for cross detection using Redis (CROSS_ABOVE/CROSS_BELOW)
 * 7. Commit offsets after processing
 * <p>
 * Redis Integration:
 * - Replaces HashMap for previous prices (distributed cache)
 * - Key pattern: PREVIOUS_PRICE:<SOURCE>:<SYMBOL>
 * - Memory-efficient: Redis optimizes small hashes
 * - TTL: 1 hour (prevents stale data)
 */
@Slf4j
@Service
public class MatchingAlertService {

    private final ActorSystem<?> actorSystem;
    private final ActorRef<AlertManagerActor.Command> alertManager;
    private final PriceAlertRepository alertRepository;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${sink-connector.topic}")
    private String inboundTopic;

    @Value("${sink-connector.alert.consumer-group:chart1m-alert-consumer}")
    private String consumerGroup;

    @Value("${sink-connector.alert.backpressure-buffer-size:1000}")
    private int backpressureBufferSize;

    public MatchingAlertService(
            ActorSystem<?> actorSystem,
            ActorRef<AlertManagerActor.Command> alertManager,
            PriceAlertRepository alertRepository,
            ObjectMapper objectMapper,
            CacheService cacheService) {
        this.actorSystem = actorSystem;
        this.alertManager = alertManager;
        this.alertRepository = alertRepository;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
    }

    /**
     * Start consuming chart1m-data topic with backpressure and automatic restart on failure
     * <p>
     * FAULT TOLERANCE FEATURES:
     * 1. Auto-restart on stream failure (exponential backoff: 3s → 30s)
     * 2. Offset commits with batching (every 100 messages or 10 seconds)
     * 3. At-least-once delivery semantics (messages may be reprocessed)
     * 4. Gap recovery: consumes from last committed offset after restart
     */
    public CompletionStage<Done> startConsuming() {
        log.info("Starting Chart1m Kafka consumer for topic: {} with fault-tolerant restart strategy", inboundTopic);

        // Configure Kafka consumer settings with fault tolerance
        ConsumerSettings<String, String> consumerSettings =
                ConsumerSettings.create(actorSystem, new StringDeserializer(), new StringDeserializer())
                        .withBootstrapServers(bootstrapServers)
                        .withGroupId(consumerGroup)
                        // IMPORTANT: Use 'earliest' for recovery - consume from last committed offset
                        // This ensures messages in the gap time (during downtime) are processed
                        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                        .withProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
                        // Increase session timeout for resilience during slow processing
                        .withProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "60000")
                        .withProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "10000")
                        // Max time between polls - prevent consumer being kicked out during processing
                        .withProperty(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "600000")
                        .withStopTimeout(Duration.ofSeconds(30));

        // Configure restart settings for automatic recovery
        RestartSettings restartSettings = RestartSettings.create(
                Duration.ofSeconds(3),    // minBackoff: initial delay before restart
                Duration.ofSeconds(30),   // maxBackoff: maximum delay between restarts
                0.2                       // randomFactor: add jitter to prevent thundering herd
        ).withMaxRestarts(10, Duration.ofMinutes(5)); // Max 10 restarts in 5 minutes window

        // Build Akka Streams pipeline with automatic restart on failure
        return RestartSource.onFailuresWithBackoff(
                        restartSettings,
                        () -> {
                            log.info("(Re)starting Kafka consumer stream for topic: {}", inboundTopic);

                            return Consumer.committableSource(consumerSettings, Subscriptions.topics(inboundTopic))
                                    // Backpressure: Buffer up to N messages
                                    .buffer(backpressureBufferSize, OverflowStrategy.backpressure())
                                    // Parse JSON to Candle1m
                                    .map(msg -> {
                                        try {
                                            String json = msg.record().value();
                                            PriceEventMessage candle = objectMapper.readValue(json, PriceEventMessage.class);
                                            return new MessageWithCommit(candle, msg.committableOffset());
                                        } catch (Exception e) {
                                            log.error("Failed to parse Candle1m from Kafka: {}", e.getMessage(), e);
                                            // Return null to filter out invalid messages
                                            return null;
                                        }
                                    })
                                    .filter(Objects::nonNull)

                                    // Process each candle
                                    .map(msg -> {
                                        doMatching(msg.event);
                                        return msg.committableOffset;
                                    })
                                    .mapAsync(3, ConsumerMessage.Committable::commitJavadsl);
                        }
                )
                .watchTermination((notUsed, terminated) -> {
                    terminated.whenComplete((done, throwable) -> {
                        if (throwable != null) {
                            log.error("Kafka consumer stream terminated with error after max retries: {}",
                                    throwable.getMessage(), throwable);
                        } else {
                            log.info("Kafka consumer stream completed successfully");
                        }
                    });
                    return terminated;
                })
                // Run the stream
                .toMat(Sink.ignore(), Keep.right())
                .run(actorSystem);
    }

    /**
     * Process a single candle - fetch alerts and forward to AlertManager
     */
    private void doMatching(PriceEventMessage event) {
        try {
            String symbol = event.getSymbol();
            String source = event.getSource();
            BigDecimal currentPrice = event.getBidPrice(); // Use close price for alert checking

            if (currentPrice == null) {
                log.warn("Candle has null close price for {}-{}, skipping", source, symbol);
                return;
            }

            // Get previous price for cross detection from Redis
            BigDecimal previousPrice = cacheService.getPreviousPrice(source, symbol);

            // Fetch active alerts for this symbol from ScyllaDB
            List<PriceAlertEntity> alerts = alertRepository.findActiveAlertsBySymbolAndSource(symbol, source, event.getBidPrice());

            if (alerts.isEmpty()) {
                log.debug("No active alerts for {}-{}, skipping", source, symbol);
            } else {
                // Register alerts with AlertManager if not already registered
                alerts.forEach(alert -> {
                    // Convert condition string to enum
                    com.example.sinkconnect.domain.logic.alert.AlertCondition condition;
                    try {
                        condition = com.example.sinkconnect.domain.logic.alert.AlertCondition.valueOf(alert.getCondition());
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid condition '{}' for alert {}, skipping", alert.getCondition(), alert.getAlertId());
                        return;
                    }

                    alertManager.tell(new AlertManagerActor.RegisterAlert(
                            alert.getAlertId(),
                            alert.getSymbol(),
                            alert.getSource(),
                            alert.getTargetPrice(),
                            condition,
                            alert.getMaxHits() != null ? alert.getMaxHits() : 10 // Default to 10 if null
                    ));
                });

                // Forward price check to AlertManager (which broadcasts to all alerts for this symbol)
                alertManager.tell(new AlertManagerActor.CheckPriceForSymbol(
                        symbol,
                        source,
                        currentPrice,
                        previousPrice
                ));

                log.info("Forwarded price check for {}-{}: current={}, previous={}, alerts={}",
                        source, symbol, currentPrice, previousPrice, alerts.size());
            }

            // Update previous price in Redis for next iteration
            cacheService.setPreviousPrice(source, symbol, currentPrice);

        } catch (Exception e) {
            log.error("Error processing candle for {}-{}: {}",
                    event.getSource(), event.getSymbol(), e.getMessage(), e);
        }
    }

    /**
     * Stop consuming (graceful shutdown)
     */
    public void stopConsuming() {
        log.info("Stopping Chart1m Kafka consumer");
        // ActorSystem shutdown will automatically stop the stream
    }

    // Helper class to carry Kafka committable offset with parsed message
    private static class MessageWithCommit {
        final PriceEventMessage event;
        final ConsumerMessage.CommittableOffset committableOffset;

        MessageWithCommit(PriceEventMessage event, ConsumerMessage.CommittableOffset committableOffset) {
            this.event = event;
            this.committableOffset = committableOffset;
        }
    }
}