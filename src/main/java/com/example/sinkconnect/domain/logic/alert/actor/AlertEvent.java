package com.example.sinkconnect.domain.logic.alert.actor;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

/**
 * Base interface for all Alert Actor events (Event Sourcing)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AlertCreated.class, name = "AlertCreated"),
        @JsonSubTypes.Type(value = AlertTriggered.class, name = "AlertTriggered"),
        @JsonSubTypes.Type(value = AlertDisabled.class, name = "AlertDisabled"),
        @JsonSubTypes.Type(value = AlertExpired.class, name = "AlertExpired")
})
public interface AlertEvent extends Serializable {
    String getAlertId();
}