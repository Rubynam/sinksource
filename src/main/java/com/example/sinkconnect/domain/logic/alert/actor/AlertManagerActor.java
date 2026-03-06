package com.example.sinkconnect.domain.logic.alert.actor;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import com.example.sinkconnect.domain.logic.alert.SymbolStatus;
import com.example.sinkconnect.domain.logic.alert.service.OutboxService;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * AlertManagerActor - Manages lifecycle of AlertActors using Cluster Sharding
 * <p>
 * Responsibilities:
 * 1. Routes commands to correct AlertActor by alertId
 * 2. Spawns new AlertActors on demand
 * 3. Broadcasts symbol status updates to all alerts for a symbol
 * 4. Manages alert registration/deregistration
 * 5. Provides query interface for active alerts
 */
@Slf4j
public class AlertManagerActor extends AbstractBehavior<AlertManagerActor.Command> {

    // Entity type key for cluster sharding
    public static final EntityTypeKey<AlertCommand> ALERT_ENTITY_KEY =
            EntityTypeKey.create(AlertCommand.class, "Alert");

    private final ClusterSharding sharding;
    private final OutboxService outboxService;

    // Track active alerts per symbol for broadcasting
    private final Map<String, Map<String, String>> symbolToAlertIds; // symbol -> {alertId -> alertId}

    // ==================== COMMANDS ====================

    public interface Command extends Serializable {
    }

    /**
     * Route a command to a specific alert
     */
    @Value
    public static class RouteToAlert implements Command {
        String alertId;
        AlertCommand alertCommand;

        @JsonCreator
        public RouteToAlert(
                @JsonProperty("alertId") String alertId,
                @JsonProperty("alertCommand") AlertCommand alertCommand) {
            this.alertId = alertId;
            this.alertCommand = alertCommand;
        }
    }

    /**
     * Register a new alert
     */
    @Value
    public static class RegisterAlert implements Command {
        String alertId;
        String symbol;
        String source;
        BigDecimal targetPrice;
        com.example.sinkconnect.domain.logic.alert.AlertCondition condition;
        int maxHits;

        @JsonCreator
        public RegisterAlert(
                @JsonProperty("alertId") String alertId,
                @JsonProperty("symbol") String symbol,
                @JsonProperty("source") String source,
                @JsonProperty("targetPrice") BigDecimal targetPrice,
                @JsonProperty("condition") com.example.sinkconnect.domain.logic.alert.AlertCondition condition,
                @JsonProperty("maxHits") int maxHits) {
            this.alertId = alertId;
            this.symbol = symbol;
            this.source = source;
            this.targetPrice = targetPrice;
            this.condition = condition;
            this.maxHits = maxHits;
        }
    }

    /**
     * Deregister an alert
     */
    @Value
    public static class DeregisterAlert implements Command {
        String alertId;
        String symbol;

        @JsonCreator
        public DeregisterAlert(
                @JsonProperty("alertId") String alertId,
                @JsonProperty("symbol") String symbol) {
            this.alertId = alertId;
            this.symbol = symbol;
        }
    }

    /**
     * Broadcast symbol status update to all alerts for this symbol
     */
    @Value
    public static class BroadcastSymbolStatus implements Command {
        String symbol;
        String source;
        SymbolStatus symbolStatus;

        @JsonCreator
        public BroadcastSymbolStatus(
                @JsonProperty("symbol") String symbol,
                @JsonProperty("source") String source,
                @JsonProperty("symbolStatus") SymbolStatus symbolStatus) {
            this.symbol = symbol;
            this.source = source;
            this.symbolStatus = symbolStatus;
        }
    }

    /**
     * Forward price check to all alerts for this symbol
     */
    @Value
    public static class CheckPriceForSymbol implements Command {
        String symbol;
        String source;
        BigDecimal currentPrice;
        BigDecimal previousPrice;

        @JsonCreator
        public CheckPriceForSymbol(
                @JsonProperty("symbol") String symbol,
                @JsonProperty("source") String source,
                @JsonProperty("currentPrice") BigDecimal currentPrice,
                @JsonProperty("previousPrice") BigDecimal previousPrice) {
            this.symbol = symbol;
            this.source = source;
            this.currentPrice = currentPrice;
            this.previousPrice = previousPrice;
        }
    }

    /**
     * Get all alerts for a symbol
     */
    @Value
    public static class GetAlertsForSymbol implements Command {
        String symbol;
        String source;
        ActorRef<GetAlertsResponse> replyTo;

        @JsonCreator
        public GetAlertsForSymbol(
                @JsonProperty("symbol") String symbol,
                @JsonProperty("source") String source,
                @JsonProperty("replyTo") ActorRef<GetAlertsResponse> replyTo) {
            this.symbol = symbol;
            this.source = source;
            this.replyTo = replyTo;
        }
    }

    @Value
    public static class GetAlertsResponse implements Serializable {
        Map<String, String> alertIds;

        @JsonCreator
        public GetAlertsResponse(@JsonProperty("alertIds") Map<String, String> alertIds) {
            this.alertIds = alertIds;
        }
    }

    // ==================== BEHAVIOR FACTORY ====================

    public static Behavior<Command> create(OutboxService outboxService) {
        return Behaviors.setup(context -> {
            ClusterSharding sharding = ClusterSharding.get(context.getSystem());

            // Initialize cluster sharding for AlertActor entities
            sharding.init(Entity.of(ALERT_ENTITY_KEY, entityContext ->
                    AlertActor.create(entityContext.getEntityId(), outboxService)
            ));

            return new AlertManagerActor(context, sharding, outboxService);
        });
    }

    private AlertManagerActor(ActorContext<Command> context,
                              ClusterSharding sharding,
                              OutboxService outboxService) {
        super(context);
        this.sharding = sharding;
        this.outboxService = outboxService;
        this.symbolToAlertIds = new HashMap<>();

        log.info("AlertManagerActor started with cluster sharding");
    }

    // ==================== MESSAGE HANDLERS ====================

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(RouteToAlert.class, this::onRouteToAlert)
                .onMessage(RegisterAlert.class, this::onRegisterAlert)
                .onMessage(DeregisterAlert.class, this::onDeregisterAlert)
                .onMessage(BroadcastSymbolStatus.class, this::onBroadcastSymbolStatus)
                .onMessage(CheckPriceForSymbol.class, this::onCheckPriceForSymbol)
                .onMessage(GetAlertsForSymbol.class, this::onGetAlertsForSymbol)
                .build();
    }

    /**
     * Route a command to a specific AlertActor (via cluster sharding)
     */
    private Behavior<Command> onRouteToAlert(RouteToAlert cmd) {
        var alertRef = sharding.entityRefFor(ALERT_ENTITY_KEY, cmd.alertId);
        alertRef.tell(cmd.alertCommand);

        log.debug("Routed command {} to alert {}", cmd.alertCommand.getClass().getSimpleName(), cmd.alertId);
        return this;
    }

    /**
     * Register a new alert and track it for symbol-based broadcasting
     */
    private Behavior<Command> onRegisterAlert(RegisterAlert cmd) {
        String symbolKey = makeSymbolKey(cmd.source, cmd.symbol);

        // Track alert in local map for broadcasting
        symbolToAlertIds.computeIfAbsent(symbolKey, k -> new HashMap<>())
                .put(cmd.alertId, cmd.alertId);

        // Initialize the AlertActor by sending CreateAlert command
        // This ensures the actor is ready to receive CheckPrice commands
        var alertRef = sharding.entityRefFor(ALERT_ENTITY_KEY, cmd.alertId);
        AlertCommand.CreateAlert createCmd = new AlertCommand.CreateAlert(
                cmd.alertId,
                cmd.symbol,
                cmd.source,
                cmd.targetPrice,
                cmd.condition,
                cmd.maxHits
        );
        alertRef.tell(createCmd);

        log.info("Registered alert {} for symbol {}-{} (total alerts for symbol: {})",
                cmd.alertId, cmd.source, cmd.symbol,
                symbolToAlertIds.get(symbolKey).size());

        return this;
    }

    /**
     * Deregister an alert (when expired or deleted)
     */
    private Behavior<Command> onDeregisterAlert(DeregisterAlert cmd) {
        symbolToAlertIds.values().forEach(alertMap -> alertMap.remove(cmd.alertId));

        log.info("Deregistered alert {} for symbol {}", cmd.alertId, cmd.symbol);
        return this;
    }

    /**
     * Broadcast symbol status update to all alerts for this symbol
     */
    private Behavior<Command> onBroadcastSymbolStatus(BroadcastSymbolStatus cmd) {
        String symbolKey = makeSymbolKey(cmd.source, cmd.symbol);
        Map<String, String> alertIds = symbolToAlertIds.get(symbolKey);

        if (alertIds == null || alertIds.isEmpty()) {
            log.debug("No active alerts for symbol {}-{}, skipping broadcast", cmd.source, cmd.symbol);
            return this;
        }

        AlertCommand.UpdateSymbolStatus updateCmd =
                new AlertCommand.UpdateSymbolStatus(cmd.symbolStatus);

        alertIds.keySet().forEach(alertId -> {
            var alertRef = sharding.entityRefFor(ALERT_ENTITY_KEY, alertId);
            alertRef.tell(updateCmd);
        });

        log.info("Broadcasted symbol status {} to {} alerts for {}-{}",
                cmd.symbolStatus, alertIds.size(), cmd.source, cmd.symbol);

        return this;
    }

    /**
     * Forward price check to all alerts for this symbol
     * This is called by Kafka consumer for each candle
     */
    private Behavior<Command> onCheckPriceForSymbol(CheckPriceForSymbol cmd) {
        String symbolKey = makeSymbolKey(cmd.source, cmd.symbol);
        Map<String, String> alertIds = symbolToAlertIds.get(symbolKey);

        if (alertIds == null || alertIds.isEmpty()) {
            log.debug("No active alerts for symbol {}-{}, skipping price check", cmd.source, cmd.symbol);
            return this;
        }

        AlertCommand.CheckPrice checkPriceCmd =
                new AlertCommand.CheckPrice(cmd.currentPrice, cmd.previousPrice);

        alertIds.keySet().forEach(alertId -> {
            var alertRef = sharding.entityRefFor(ALERT_ENTITY_KEY, alertId);
            alertRef.tell(checkPriceCmd);
        });

        log.debug("Forwarded price check (price={}) to {} alerts for {}-{}",
                cmd.currentPrice, alertIds.size(), cmd.source, cmd.symbol);

        return this;
    }

    /**
     * Get all alert IDs for a symbol (for queries)
     */
    private Behavior<Command> onGetAlertsForSymbol(GetAlertsForSymbol cmd) {
        String symbolKey = makeSymbolKey(cmd.source, cmd.symbol);
        Map<String, String> alertIds = symbolToAlertIds.getOrDefault(symbolKey, new HashMap<>());

        cmd.replyTo.tell(new GetAlertsResponse(new HashMap<>(alertIds)));

        log.debug("Returned {} alerts for symbol {}-{}", alertIds.size(), cmd.source, cmd.symbol);
        return this;
    }

    // ==================== HELPER METHODS ====================

    private String makeSymbolKey(String source, String symbol) {
        return source + ":" + symbol;
    }
}