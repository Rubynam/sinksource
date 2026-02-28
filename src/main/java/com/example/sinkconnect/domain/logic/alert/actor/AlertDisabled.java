package com.example.sinkconnect.domain.logic.alert.actor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

import java.time.Instant;

/**
 * Event: Alert was disabled (manually or due to symbol status)
 */
@Value
public class AlertDisabled implements AlertEvent {
    String alertId;
    String reason;
    Instant disabledAt;

    @JsonCreator
    public AlertDisabled(
            @JsonProperty("alertId") String alertId,
            @JsonProperty("reason") String reason,
            @JsonProperty("disabledAt") Instant disabledAt) {
        this.alertId = alertId;
        this.reason = reason;
        this.disabledAt = disabledAt;
    }
}