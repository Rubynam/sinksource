package com.example.sinkconnect.domain.logic.alert.actor;

import com.example.sinkconnect.domain.logic.alert.AlertCondition;
import com.example.sinkconnect.domain.logic.alert.SymbolStatus;
import lombok.Value;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Commands that can be sent to AlertActor
 */
public interface AlertCommand extends Serializable {

    /**
     * Create a new alert
     */
    @Value
    class CreateAlert implements AlertCommand {
        String alertId;
        String symbol;
        String source;
        BigDecimal targetPrice;
        AlertCondition condition;
        int maxHits;
    }

    /**
     * Check price against alert condition
     */
    @Value
    class CheckPrice implements AlertCommand {
        BigDecimal currentPrice;
        BigDecimal previousPrice;  // For cross detection
    }

    /**
     * Update symbol status (from external source)
     */
    @Value
    class UpdateSymbolStatus implements AlertCommand {
        SymbolStatus symbolStatus;
    }

    /**
     * Manually disable alert
     */
    @Value
    class DisableAlert implements AlertCommand {
        String reason;
    }

    /**
     * Get current alert state
     */
    class GetState implements AlertCommand {
        private static final GetState INSTANCE = new GetState();

        private GetState() {}

        public static GetState getInstance() {
            return INSTANCE;
        }
    }
}