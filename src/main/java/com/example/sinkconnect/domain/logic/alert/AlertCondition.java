package com.example.sinkconnect.domain.logic.alert;

public enum AlertCondition {
    ABOVE,         // Price >= target_price
    BELOW,         // Price <= target_price
    CROSS_ABOVE,   // Price crosses above target_price
    CROSS_BELOW    // Price crosses below target_price
}