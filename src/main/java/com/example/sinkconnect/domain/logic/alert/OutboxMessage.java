package com.example.sinkconnect.domain.logic.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxMessage implements Serializable {

    private UUID outboxId;
    private String alertId;
    private String symbol;
    private String source;
    private OutboxMessageType messageType;
    private String payload;  // JSON payload
    private OutboxStatus status;
    private Instant createdAt;
    private Instant processedAt;
    private int retryCount;
    private String errorMessage;

    public static OutboxMessage createPending(String alertId, String symbol, String source,
                                               OutboxMessageType messageType, String payload) {
        return OutboxMessage.builder()
                .outboxId(UUID.randomUUID())
                .alertId(alertId)
                .symbol(symbol)
                .source(source)
                .messageType(messageType)
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .createdAt(Instant.now())
                .retryCount(0)
                .build();
    }
}