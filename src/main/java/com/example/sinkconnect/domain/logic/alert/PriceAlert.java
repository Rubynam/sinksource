package com.example.sinkconnect.domain.logic.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceAlert implements Serializable {

    private String alertId;
    private String symbol;
    private String source;
    private BigDecimal targetPrice;
    private AlertCondition condition;
    private AlertStatus status;
    private int hitCount;
    private int maxHits;  // Default: 10
    private Instant createdAt;
    private Instant updatedAt;

    public boolean hasReachedLimit() {
        return hitCount >= maxHits;
    }

    public boolean isEnabled() {
        return status == AlertStatus.ENABLED;
    }

    public void incrementHitCount() {
        this.hitCount++;
        this.updatedAt = Instant.now();
        if (hasReachedLimit()) {
            this.status = AlertStatus.EXPIRED;
        }
    }
}