package com.example.sinkconnect.domain.logic.alert.actor;

import com.example.sinkconnect.domain.logic.alert.AlertCondition;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event: Alert was created
 */
@Value
public class AlertCreated implements AlertEvent {
    String alertId;
    String symbol;
    String source;
    BigDecimal targetPrice;
    AlertCondition condition;
    int maxHits;
    Instant createdAt;

    @JsonCreator
    public AlertCreated(
            @JsonProperty("alertId") String alertId,
            @JsonProperty("symbol") String symbol,
            @JsonProperty("source") String source,
            @JsonProperty("targetPrice") BigDecimal targetPrice,
            @JsonProperty("condition") AlertCondition condition,
            @JsonProperty("maxHits") int maxHits,
            @JsonProperty("createdAt") Instant createdAt) {
        this.alertId = alertId;
        this.symbol = symbol;
        this.source = source;
        this.targetPrice = targetPrice;
        this.condition = condition;
        this.maxHits = maxHits;
        this.createdAt = createdAt;
    }
}