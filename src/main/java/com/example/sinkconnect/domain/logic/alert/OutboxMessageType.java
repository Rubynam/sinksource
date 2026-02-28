package com.example.sinkconnect.domain.logic.alert;

public enum OutboxMessageType {
    ALERT_TRIGGERED,         // Alert condition met
    ALERT_LIMIT_REACHED,     // Hit count limit reached
    SYMBOL_DISABLED          // Symbol was disabled during processing
}