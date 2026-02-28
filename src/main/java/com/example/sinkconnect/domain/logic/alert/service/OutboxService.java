package com.example.sinkconnect.domain.logic.alert.service;

import com.example.sinkconnect.domain.logic.alert.OutboxMessageType;
import com.example.sinkconnect.domain.logic.alert.OutboxStatus;
import com.example.sinkconnect.infrastructure.entity.OutboxEntity;
import com.example.sinkconnect.infrastructure.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Save outbox message in the same transaction as alert state update
     * This ensures atomicity (Outbox Pattern)
     */
    @Transactional
    public void saveOutboxMessage(String alertId, String symbol, String source,
                                   OutboxMessageType messageType, Map<String, Object> payloadData) {
        try {
            String payload = objectMapper.writeValueAsString(payloadData);

            OutboxEntity entity = OutboxEntity.builder()
                    .outboxId(UUID.randomUUID())
                    .alertId(alertId)
                    .symbol(symbol)
                    .source(source)
                    .messageType(messageType.name())
                    .payload(payload)
                    .status(OutboxStatus.PENDING.name())
                    .createdAt(Instant.now())
                    .retryCount(0)
                    .build();

            outboxRepository.save(entity);

            log.info("Saved outbox message for alert {} with type {}", alertId, messageType);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox payload for alert {}: {}", alertId, e.getMessage(), e);
            throw new RuntimeException("Failed to save outbox message", e);
        }
    }

    /**
     * Convenience method for alert triggered notification
     */
    public void saveAlertTriggeredNotification(String alertId, String symbol, String source,
                                                 String targetPrice, String currentPrice, int hitCount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("alertId", alertId);
        payload.put("symbol", symbol);
        payload.put("source", source);
        payload.put("targetPrice", targetPrice);
        payload.put("currentPrice", currentPrice);
        payload.put("hitCount", hitCount);
        payload.put("triggeredAt", Instant.now().toString());

        saveOutboxMessage(alertId, symbol, source, OutboxMessageType.ALERT_TRIGGERED, payload);
    }

    /**
     * Convenience method for alert limit reached notification
     */
    public void saveAlertLimitReachedNotification(String alertId, String symbol, String source,
                                                    int maxHits) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("alertId", alertId);
        payload.put("symbol", symbol);
        payload.put("source", source);
        payload.put("maxHits", maxHits);
        payload.put("message", "Alert has reached maximum hit count and will be expired");
        payload.put("expiredAt", Instant.now().toString());

        saveOutboxMessage(alertId, symbol, source, OutboxMessageType.ALERT_LIMIT_REACHED, payload);
    }

    /**
     * Update outbox message status after processing
     */
    @Transactional
    public void updateOutboxStatus(UUID outboxId, String oldStatus, Instant createdAt,
                                     OutboxStatus newStatus, String errorMessage) {
        // In Cassandra, we can't update partition key, so we need to delete and insert
        OutboxEntity oldEntity = OutboxEntity.builder()
                .status(oldStatus)
                .createdAt(createdAt)
                .outboxId(outboxId)
                .build();

        outboxRepository.delete(oldEntity);

        // Create new entity with updated status
        OutboxEntity newEntity = OutboxEntity.builder()
                .outboxId(outboxId)
                .status(newStatus.name())
                .createdAt(createdAt)
                .processedAt(Instant.now())
                .errorMessage(errorMessage)
                .build();

        outboxRepository.save(newEntity);

        log.debug("Updated outbox {} status from {} to {}", outboxId, oldStatus, newStatus);
    }
}