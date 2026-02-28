package com.example.sinkconnect.domain.logic.alert;

public enum OutboxStatus {
    PENDING,      // Ready to be processed
    PROCESSING,   // Currently being sent
    SENT,         // Successfully delivered
    FAILED        // Failed after retries
}