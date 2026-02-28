package com.example.sinkconnect.domain.logic.alert.actor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

import java.time.Instant;

/**
 * Event: Alert reached maximum hit count
 */
@Value
public class AlertExpired implements AlertEvent {
    String alertId;
    int finalHitCount;
    Instant expiredAt;

    @JsonCreator
    public AlertExpired(
            @JsonProperty("alertId") String alertId,
            @JsonProperty("finalHitCount") int finalHitCount,
            @JsonProperty("expiredAt") Instant expiredAt) {
        this.alertId = alertId;
        this.finalHitCount = finalHitCount;
        this.expiredAt = expiredAt;
    }
}