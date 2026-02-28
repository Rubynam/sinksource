package com.example.sinkconnect.domain.logic.alert.actor;

import com.example.sinkconnect.domain.logic.alert.AlertCondition;
import com.example.sinkconnect.domain.logic.alert.AlertStatus;
import com.example.sinkconnect.domain.logic.alert.SymbolStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;
import lombok.With;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable state for AlertActor (Event Sourcing)
 */
@Value
@With
public class AlertState implements Serializable {

    String alertId;
    String symbol;
    String source;
    BigDecimal targetPrice;
    AlertCondition condition;
    AlertStatus status;
    int hitCount;
    int maxHits;
    SymbolStatus symbolStatus;
    BigDecimal lastPrice;
    Instant createdAt;
    Instant updatedAt;

    @JsonCreator
    public AlertState(
            @JsonProperty("alertId") String alertId,
            @JsonProperty("symbol") String symbol,
            @JsonProperty("source") String source,
            @JsonProperty("targetPrice") BigDecimal targetPrice,
            @JsonProperty("condition") AlertCondition condition,
            @JsonProperty("status") AlertStatus status,
            @JsonProperty("hitCount") int hitCount,
            @JsonProperty("maxHits") int maxHits,
            @JsonProperty("symbolStatus") SymbolStatus symbolStatus,
            @JsonProperty("lastPrice") BigDecimal lastPrice,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt) {
        this.alertId = alertId;
        this.symbol = symbol;
        this.source = source;
        this.targetPrice = targetPrice;
        this.condition = condition;
        this.status = status;
        this.hitCount = hitCount;
        this.maxHits = maxHits;
        this.symbolStatus = symbolStatus;
        this.lastPrice = lastPrice;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Empty state for initialization
     */
    public static AlertState empty() {
        return new AlertState(null, null, null, null, null,
                AlertStatus.DISABLED, 0, 10, SymbolStatus.ENABLED, null, null, null);
    }

    /**
     * Create initial state from AlertCreated event
     */
    public static AlertState fromCreatedEvent(AlertCreated event) {
        return new AlertState(
                event.getAlertId(),
                event.getSymbol(),
                event.getSource(),
                event.getTargetPrice(),
                event.getCondition(),
                AlertStatus.ENABLED,
                0,
                event.getMaxHits(),
                SymbolStatus.ENABLED,
                null,
                event.getCreatedAt(),
                event.getCreatedAt()
        );
    }

    /**
     * Apply AlertTriggered event
     */
    public AlertState applyTriggered(AlertTriggered event) {
        int newHitCount = event.getHitCount();
        AlertStatus newStatus = newHitCount >= maxHits ? AlertStatus.EXPIRED : AlertStatus.TRIGGERED;

        return this.withHitCount(newHitCount)
                .withStatus(newStatus)
                .withUpdatedAt(event.getTriggeredAt())
                .withLastPrice(event.getTriggerPrice());
    }

    /**
     * Apply AlertDisabled event
     */
    public AlertState applyDisabled(AlertDisabled event) {
        return this.withStatus(AlertStatus.DISABLED)
                .withUpdatedAt(event.getDisabledAt());
    }

    /**
     * Apply AlertExpired event
     */
    public AlertState applyExpired(AlertExpired event) {
        return this.withStatus(AlertStatus.EXPIRED)
                .withHitCount(event.getFinalHitCount())
                .withUpdatedAt(event.getExpiredAt());
    }

    /**
     * Update symbol status
     */
    public AlertState updateSymbolStatus(SymbolStatus newStatus) {
        return this.withSymbolStatus(newStatus);
    }

    /**
     * Check if alert is active
     */
    public boolean isActive() {
        return status == AlertStatus.ENABLED && symbolStatus == SymbolStatus.ENABLED;
    }

    /**
     * Check if price matches the alert condition
     */
    public boolean matchesCondition(BigDecimal currentPrice, BigDecimal previousPrice) {
        if (currentPrice == null || targetPrice == null) {
            return false;
        }

        return switch (condition) {
            case ABOVE -> currentPrice.compareTo(targetPrice) >= 0;
            case BELOW -> currentPrice.compareTo(targetPrice) <= 0;
            case CROSS_ABOVE -> {
                if (previousPrice == null) yield false;
                yield previousPrice.compareTo(targetPrice) < 0 && currentPrice.compareTo(targetPrice) >= 0;
            }
            case CROSS_BELOW -> {
                if (previousPrice == null) yield false;
                yield previousPrice.compareTo(targetPrice) > 0 && currentPrice.compareTo(targetPrice) <= 0;
            }
        };
    }

    public boolean hasReachedLimit() {
        return hitCount >= maxHits;
    }
}