package com.example.sinkconnect.domain.logic.alert.actor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event: Alert condition was met
 */
@Value
public class AlertTriggered implements AlertEvent {
    String alertId;
    BigDecimal triggerPrice;
    int hitCount;
    Instant triggeredAt;

    @JsonCreator
    public AlertTriggered(
            @JsonProperty("alertId") String alertId,
            @JsonProperty("triggerPrice") BigDecimal triggerPrice,
            @JsonProperty("hitCount") int hitCount,
            @JsonProperty("triggeredAt") Instant triggeredAt) {
        this.alertId = alertId;
        this.triggerPrice = triggerPrice;
        this.hitCount = hitCount;
        this.triggeredAt = triggeredAt;
    }
}