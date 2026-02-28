package com.example.sinkconnect.domain.logic.alert.service;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.kafka.CommitterSettings;
import akka.kafka.ConsumerSettings;
import akka.kafka.Subscriptions;
import akka.kafka.javadsl.Consumer;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import com.example.sinkconnect.domain.common.model.Candle1m;
import com.example.sinkconnect.domain.logic.alert.actor.AlertManagerActor;
import com.example.sinkconnect.infrastructure.entity.PriceAlertEntity;
import com.example.sinkconnect.infrastructure.repository.PriceAlertRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Chart1mConsumerService - Akka Streams Kafka Consumer with Backpressure
 *
 * Responsibilities:
 * 1. Consume chart1m-data topic using Akka Streams
 * 2. Implement backpressure to prevent actor mailbox overflow
 * 3. Group messages by (source, symbol)
 * 4. Fetch active alerts for each symbol from ScyllaDB
 * 5. Forward CheckPrice commands to AlertManagerActor
 * 6. Track previous price for cross detection (CROSS_ABOVE/CROSS_BELOW)
 * 7. Commit offsets after processing
 */
@Slf4j
@Service
public class Chart1mConsumerService {

    private final ActorSystem<?> actorSystem;
    private final ActorRef<AlertManagerActor.Command> alertManager;
    private final PriceAlertRepository alertRepository;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${sink-connector.topics.chart1m-output:chart1m-data}")
    private String chart1mTopic;

    @Value("${sink-connector.alert.consumer-group:chart1m-alert-consumer}")
    private String consumerGroup;

    @Value("${sink-connector.alert.backpressure-buffer-size:1000}")
    private int backpressureBufferSize;

    // Track previous prices for cross detection
    private final Map<String, BigDecimal> previousPrices = new HashMap<>();

    public Chart1mConsumerService(
            ActorSystem<?> actorSystem,
            ActorRef<AlertManagerActor.Command> alertManager,
            PriceAlertRepository alertRepository,
            ObjectMapper objectMapper) {
        this.actorSystem = actorSystem;
        this.alertManager = alertManager;
        this.alertRepository = alertRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Start consuming chart1m-data topic with backpressure
     */
    public CompletionStage<Done> startConsuming() {
        log.info("Starting Chart1m Kafka consumer for topic: {}", chart1mTopic);

        // Configure Kafka consumer settings
        ConsumerSettings<String, String> consumerSettings =
                ConsumerSettings.create(actorSystem, new StringDeserializer(), new StringDeserializer())
                        .withBootstrapServers(bootstrapServers)
                        .withGroupId(consumerGroup)
                        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                        .withProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
                        .withStopTimeout(Duration.ofSeconds(30));

        // Configure committer settings for manual offset commit
        CommitterSettings committerSettings = CommitterSettings.create(actorSystem);

        // Build Akka Streams pipeline with backpressure
        return Consumer.committableSource(consumerSettings, Subscriptions.topics(chart1mTopic))
                // Backpressure: Buffer up to N messages
                .buffer(backpressureBufferSize, akka.stream.OverflowStrategy.backpressure())

                // Parse JSON to Candle1m
                .map(msg -> {
                    try {
                        String json = msg.record().value();
                        Candle1m candle = objectMapper.readValue(json, Candle1m.class);
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
                    processCandle(msg.candle);
                    return msg.committableOffset;
                })

                // Batch commit offsets for performance
                .batch(100, akka.kafka.javadsl.Committer::offsetsFromOffset, (batch, offset) -> {
                    batch.updated(offset);
                    return batch;
                })
                .mapAsync(3, batch -> batch.commitJavadsl())

                // Run the stream
                .toMat(Sink.ignore(), Keep.right())
                .run(actorSystem);
    }

    /**
     * Process a single candle - fetch alerts and forward to AlertManager
     */
    private void processCandle(Candle1m candle) {
        try {
            String symbol = candle.getSymbol();
            String source = candle.getSource();
            BigDecimal currentPrice = candle.getClose(); // Use close price for alert checking

            if (currentPrice == null) {
                log.warn("Candle has null close price for {}-{}, skipping", source, symbol);
                return;
            }

            // Get previous price for cross detection
            String priceKey = makePriceKey(source, symbol);
            BigDecimal previousPrice = previousPrices.get(priceKey);

            // Fetch active alerts for this symbol from ScyllaDB
            List<PriceAlertEntity> alerts = alertRepository.findActiveAlertsBySymbolAndSource(symbol, source);

            if (alerts.isEmpty()) {
                log.debug("No active alerts for {}-{}, skipping", source, symbol);
            } else {
                // Register alerts with AlertManager if not already registered
                alerts.forEach(alert -> {
                    alertManager.tell(new AlertManagerActor.RegisterAlert(
                            alert.getAlertId(),
                            alert.getSymbol(),
                            alert.getSource()
                    ));
                });

                // Forward price check to AlertManager (which broadcasts to all alerts for this symbol)
                alertManager.tell(new AlertManagerActor.CheckPriceForSymbol(
                        symbol,
                        source,
                        currentPrice,
                        previousPrice
                ));

                log.debug("Forwarded price check for {}-{}: current={}, previous={}, alerts={}",
                        source, symbol, currentPrice, previousPrice, alerts.size());
            }

            // Update previous price for next iteration
            previousPrices.put(priceKey, currentPrice);

        } catch (Exception e) {
            log.error("Error processing candle for {}-{}: {}",
                    candle.getSource(), candle.getSymbol(), e.getMessage(), e);
        }
    }

    /**
     * Stop consuming (graceful shutdown)
     */
    public void stopConsuming() {
        log.info("Stopping Chart1m Kafka consumer");
        // ActorSystem shutdown will automatically stop the stream
    }

    private String makePriceKey(String source, String symbol) {
        return source + ":" + symbol;
    }

    // Helper class to carry Kafka committable offset with parsed message
    private static class MessageWithCommit {
        final Candle1m candle;
        final akka.kafka.javadsl.Consumer.CommittableOffset committableOffset;

        MessageWithCommit(Candle1m candle, akka.kafka.javadsl.Consumer.CommittableOffset committableOffset) {
            this.candle = candle;
            this.committableOffset = committableOffset;
        }
    }
}