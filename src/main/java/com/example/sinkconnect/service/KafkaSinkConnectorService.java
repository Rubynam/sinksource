package com.example.sinkconnect.service;

import com.example.sinkconnect.domain.PriceEventMessage;
import com.example.sinkconnect.infrastructure.entity.Candle1mEntity;
import com.example.sinkconnect.infrastructure.repository.Candle1mRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaSinkConnectorService {

    private final Candle1mRepository candle1mRepository;
    private final RetryTemplate retryTemplate;
    private final Validator validator;

    private final Map<String, List<PriceEventMessage>> priceBuffer = new HashMap<>();
    private static final int BUFFER_SIZE = 100;

    @KafkaListener(
            topics = {"${sink-connector.topics.binance}", "${sink-connector.topics.huobi}"},
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumePriceEvents(
            @Payload PriceEventMessage message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        try {
            log.debug("Received message from topic: {}, partition: {}, offset: {}",
                    topic, partition, offset);

            var violations = validator.validate(message);
            if (!violations.isEmpty()) {
                log.error("Validation failed for message: {}, violations: {}",
                        message, violations);
                acknowledgment.acknowledge();
                return;
            }

            String bufferKey = message.getSource() + "_" + message.getSymbol();

            synchronized (priceBuffer) {
                priceBuffer.computeIfAbsent(bufferKey, k -> new ArrayList<>()).add(message);

                if (priceBuffer.get(bufferKey).size() >= BUFFER_SIZE) {
                    flushBuffer(bufferKey);
                }
            }

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Error processing message from topic: {}", topic, e);
            throw new RuntimeException("Message processing failed", e);
        }
    }

    private void flushBuffer(String bufferKey) {
        List<PriceEventMessage> messages;

        synchronized (priceBuffer) {
            messages = new ArrayList<>(priceBuffer.get(bufferKey));
            priceBuffer.get(bufferKey).clear();
        }

        if (messages.isEmpty()) {
            return;
        }

        try {
            retryTemplate.execute(context -> {
                log.info("Flushing {} messages to ScyllaDB (attempt: {})",
                        messages.size(), context.getRetryCount() + 1);
                processAndStore(messages);
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to flush buffer after retries for key: {}", bufferKey, e);
        }
    }

    private void processAndStore(List<PriceEventMessage> messages) {
        Map<String, CandleAggregator> aggregators = new HashMap<>();

        for (PriceEventMessage message : messages) {
            String key = generateAggregationKey(message);
            aggregators.computeIfAbsent(key, k -> new CandleAggregator(message))
                    .addPrice(message);
        }

        List<Candle1mEntity> entities = new ArrayList<>();
        for (CandleAggregator aggregator : aggregators.values()) {
            entities.add(aggregator.toEntity());
        }

        if (!entities.isEmpty()) {
            candle1mRepository.saveAll(entities);
            log.info("Saved {} candles to ScyllaDB", entities.size());
        }
    }

    private String generateAggregationKey(PriceEventMessage message) {
        long timestamp = message.getTimestamp();
        long minuteTimestamp = (timestamp / 60000) * 60000;
        return message.getSource() + "_" + message.getSymbol() + "_" + minuteTimestamp;
    }

    private String generateOmsServerId(String source, long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDate date = instant.atZone(ZoneId.systemDefault()).toLocalDate();
        return source + "_" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private static class CandleAggregator {
        private final String source;
        private final String symbol;
        private final long timestamp;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;
        private long tickCount;

        public CandleAggregator(PriceEventMessage firstMessage) {
            this.source = firstMessage.getSource();
            this.symbol = firstMessage.getSymbol();
            this.timestamp = (firstMessage.getTimestamp() / 60000) * 60000;
            this.open = firstMessage.getBidPrice();
            this.high = firstMessage.getBidPrice();
            this.low = firstMessage.getAskPrice();
            this.close = firstMessage.getAskPrice();
            this.volume = firstMessage.getBidQty();
            this.tickCount = 1;
        }

        public void addPrice(PriceEventMessage message) {
            if (message.getBidPrice().compareTo(high) > 0) {
                high = message.getBidPrice();
            }
            if (message.getAskPrice().compareTo(low) < 0) {
                low = message.getAskPrice();
            }
            close = message.getAskPrice();
            volume = volume.add(message.getBidQty());
            tickCount++;
        }

        public Candle1mEntity toEntity() {
            Instant instant = Instant.ofEpochMilli(timestamp);
            LocalDate date = instant.atZone(ZoneId.systemDefault()).toLocalDate();
            String omsServerId = source + "_" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            return Candle1mEntity.builder()
                    .source(source)
                    .symbol(symbol)
                    .omsServerId(omsServerId)
                    .timestamp(instant)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .tickCount(tickCount)
                    .createdAt(Instant.now())
                    .build();
        }
    }

    public void flushAllBuffers() {
        synchronized (priceBuffer) {
            for (String key : new ArrayList<>(priceBuffer.keySet())) {
                flushBuffer(key);
            }
        }
    }
}