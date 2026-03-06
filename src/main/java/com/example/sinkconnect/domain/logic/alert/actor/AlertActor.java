package com.example.sinkconnect.domain.logic.alert.actor;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.japi.function.Function;
import org.apache.pekko.japi.function.Procedure;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.*;
import com.example.sinkconnect.domain.logic.alert.AlertStatus;
import com.example.sinkconnect.domain.logic.alert.SymbolStatus;
import com.example.sinkconnect.domain.logic.alert.service.OutboxService;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * AlertActor - Event Sourced Actor for Price Alert Processing
 * <p>
 * Responsibilities:
 * 1. Maintains alert state (hitCount, status, symbolStatus)
 * 2. Matches incoming prices against alert conditions
 * 3. Enforces hit count limit (max 10)
 * 4. Checks symbol status before processing
 * 5. Uses Outbox Pattern for notifications (no dual writes)
 * 6. Persists events for state recovery
 */
@Slf4j
public class AlertActor extends EventSourcedBehavior<AlertCommand, AlertEvent, AlertState> { //thread model

    private final OutboxService outboxService;
    private final String alertId;

    public AlertActor(String alertId, OutboxService outboxService) {
        super(PersistenceId.of("UserAlert", alertId));
        this.alertId = alertId;
        this.outboxService = outboxService;
    }

    @Override
    public AlertState emptyState() {
        return AlertState.empty();
    }

    // ==================== COMMAND HANDLERS ====================

    @Override
    public CommandHandler<AlertCommand, AlertEvent, AlertState> commandHandler() {
        return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(AlertCommand.CreateAlert.class, this::onCreateAlert)
                .onCommand(AlertCommand.CheckPrice.class, this::onCheckPrice)
                .onCommand(AlertCommand.UpdateSymbolStatus.class, this::onUpdateSymbolStatus)
                .onCommand(AlertCommand.DisableAlert.class, this::onDisableAlert)
                .onCommand(AlertCommand.GetState.class, this::onGetState)
                .build();
    }

    /**
     * Handle CreateAlert command
     */
    private Effect<AlertEvent, AlertState> onCreateAlert(
            AlertState state, AlertCommand.CreateAlert cmd) {
        log.info("create a new alert {} cmd alertId {}", state.getAlertId(), cmd.getAlertId());
        if (state.getAlertId() != null) {
            log.warn("Alert {} already exists, ignoring create command", cmd.getAlertId());
            return Effect().none();
        }

        AlertCreated event = new AlertCreated(
                cmd.getAlertId(),
                cmd.getSymbol(),
                cmd.getSource(),
                cmd.getTargetPrice(),
                cmd.getCondition(),
                cmd.getMaxHits(),
                Instant.now()
        );

        return Effect()
                .persist(event)
                .thenRun(() -> log.info("Alert {} created for {}-{} with target price {}",
                        cmd.getAlertId(), cmd.getSource(), cmd.getSymbol(), cmd.getTargetPrice()));
    }

    public static Behavior<AlertCommand> create(String alertId, OutboxService outboxService) {
//        return Behaviors.setup(context -> new AlertActor(alertId, outboxService));
        return Behaviors.setup(new Function<ActorContext<AlertCommand>, Behavior<AlertCommand>>() {
            @Override
            public Behavior<AlertCommand> apply(ActorContext<AlertCommand> param) throws Exception, Exception {
                return new AlertActor(alertId, outboxService);
            }
        });
    }

    /**
     * Handle CheckPrice command - CORE BUSINESS LOGIC
     */
    private Effect<AlertEvent, AlertState> onCheckPrice(
            AlertState state, AlertCommand.CheckPrice cmd) {

        // Guard 1: Check if alert is initialized
        if (state.getAlertId() == null) {
            log.warn("Alert {} not initialized, ignoring price check", alertId);
            return Effect().none();
        }

        // Guard 2: Check if alert is active (not disabled/expired)
        if (!state.isActive()) {
            log.debug("Alert {} is not active (status={}, symbolStatus={}), dropping price event",
                    alertId, state.getStatus(), state.getSymbolStatus());
            return Effect().none();
        }

        // Guard 3: Check if symbol is enabled
        if (state.getSymbolStatus() == SymbolStatus.DISABLED) {
            log.info("Symbol {}-{} is DISABLED, dropping price event for alert {}",
                    state.getSource(), state.getSymbol(), alertId);
            return Effect().none();
        }

        // Guard 4: Check if already reached limit
        if (state.hasReachedLimit()) {
            log.debug("Alert {} has already reached limit, ignoring price", alertId);
            return Effect().none();
        }

        // Business Logic: Check if price matches condition
        boolean matches = state.matchesCondition(cmd.getCurrentPrice(), cmd.getPreviousPrice());

        if (!matches) {
            log.debug("Price {} does not match condition {} for alert {}",
                    cmd.getCurrentPrice(), state.getCondition(), alertId);
            return Effect().none();
        }

        // Price matched! Increment hit count
        int newHitCount = state.getHitCount() + 1;
        AlertTriggered event = new AlertTriggered(
                alertId,
                cmd.getCurrentPrice(),
                newHitCount,
                Instant.now()
        );

        // Check if this trigger will reach the limit
        if (newHitCount >= state.getMaxHits()) {
            // Hit limit reached - persist AlertTriggered then AlertExpired
            return Effect()
                    .persist(event)
                    .thenRun(() -> {
                        log.info("Alert {} triggered (hit {}/{}). Limit REACHED - expiring alert",
                                alertId, newHitCount, state.getMaxHits());

                        // Save to outbox AFTER event is persisted (transactional)
                        outboxService.saveAlertTriggeredNotification(
                                alertId,
                                state.getSymbol(),
                                state.getSource(),
                                state.getTargetPrice().toPlainString(),
                                cmd.getCurrentPrice().toPlainString(),
                                newHitCount
                        );

                        // Save limit reached notification
                        outboxService.saveAlertLimitReachedNotification(
                                alertId,
                                state.getSymbol(),
                                state.getSource(),
                                state.getMaxHits()
                        );
                    })
                    .thenRun((Procedure<AlertState>) param -> param.applyExpired(new AlertExpired(alertId, newHitCount, Instant.now())))
                    .thenRun(() -> log.info("Alert {} expired after reaching hit limit", alertId));
        } else {
            // Normal trigger - persist event and save to outbox
            return Effect()
                    .persist(event)
                    .thenRun(() -> {
                        log.info("Alert {} triggered (hit {}/{}). Price: {} matches condition {}",
                                alertId, newHitCount, state.getMaxHits(),
                                cmd.getCurrentPrice(), state.getCondition());

                        // Save to outbox AFTER event is persisted (Outbox Pattern)
                        outboxService.saveAlertTriggeredNotification(
                                alertId,
                                state.getSymbol(),
                                state.getSource(),
                                state.getTargetPrice().toPlainString(),
                                cmd.getCurrentPrice().toPlainString(),
                                newHitCount
                        );
                    });
        }
    }

    /**
     * Handle UpdateSymbolStatus command
     */
    private Effect<AlertEvent, AlertState> onUpdateSymbolStatus(
            AlertState state, AlertCommand.UpdateSymbolStatus cmd) {

        if (state.getAlertId() == null) {
            log.warn("Alert {} not initialized, ignoring symbol status update", alertId);
            return Effect().none();
        }

        if (state.getSymbolStatus() == cmd.getSymbolStatus()) {
            // No change
            return Effect().none();
        }

        if (cmd.getSymbolStatus() == SymbolStatus.DISABLED) {
            // Symbol disabled - disable alert
            AlertDisabled event = new AlertDisabled(
                    alertId,
                    "Symbol disabled",
                    Instant.now()
            );

            return Effect()
                    .persist(event)
                    .thenRun(() -> log.info("Alert {} disabled due to symbol status change to DISABLED", alertId));
        } else {
            // Symbol re-enabled - just update state without persisting event
            // (We don't have a SymbolStatusUpdated event, so we update state directly)
            log.info("Symbol {}-{} re-enabled for alert {}",
                    state.getSource(), state.getSymbol(), alertId);
            return Effect().none().thenRun(() -> {
                // Note: In a real implementation, you might want to persist this as an event
                // For now, we rely on external updates
            });
        }
    }

    /**
     * Handle DisableAlert command
     */
    private Effect<AlertEvent, AlertState> onDisableAlert(
            AlertState state, AlertCommand.DisableAlert cmd) {

        if (state.getAlertId() == null) {
            log.warn("Alert {} not initialized, ignoring disable command", alertId);
            return Effect().none();
        }

        if (state.getStatus() == AlertStatus.DISABLED) {
            log.debug("Alert {} already disabled", alertId);
            return Effect().none();
        }

        AlertDisabled event = new AlertDisabled(
                alertId,
                cmd.getReason(),
                Instant.now()
        );

        return Effect()
                .persist(event)
                .thenRun(() -> log.info("Alert {} disabled. Reason: {}", alertId, cmd.getReason()));
    }

    /**
     * Handle GetState command
     */
    private Effect<AlertEvent, AlertState> onGetState(
            AlertState state, AlertCommand.GetState cmd) {

        log.debug("Current state for alert {}: status={}, hitCount={}/{}, symbolStatus={}",
                alertId, state.getStatus(), state.getHitCount(), state.getMaxHits(), state.getSymbolStatus());

        return Effect().none();
    }

    // ==================== EVENT HANDLERS (State Recovery) ====================

    @Override
    public EventHandler<AlertState, AlertEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(AlertCreated.class, (state, event) -> AlertState.fromCreatedEvent(event))
                .onEvent(AlertTriggered.class, (state, event) -> state.applyTriggered(event))
                .onEvent(AlertDisabled.class, (state, event) -> state.applyDisabled(event))
                .onEvent(AlertExpired.class, (state, event) -> state.applyExpired(event))
                .build();
    }

    // ==================== SNAPSHOT SUPPORT (Optional Performance Optimization) ====================

    @Override
    public RetentionCriteria retentionCriteria() {
        // Keep snapshots and delete old events to prevent unbounded journal growth
        return RetentionCriteria.snapshotEvery(100, 2);
    }

    @Override
    public boolean shouldSnapshot(AlertState state, AlertEvent event, long sequenceNr) {
        // Snapshot every 100 events
        return sequenceNr % 100 == 0;
    }
}