package com.example.sinkconnect.domain.logic.alert.service;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import com.example.sinkconnect.domain.logic.alert.actor.AlertManagerActor;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletionStage;

/**
 * AlertSystemService - Main coordinator for the Alert System
 *
 * Responsibilities:
 * 1. Start Chart1m Kafka consumer on application startup
 * 2. Initialize alert system components
 * 3. Coordinate graceful shutdown
 * 4. Provide system health status
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertSystemService {

    private final ActorSystem<Void> actorSystem;
    private final ActorRef<AlertManagerActor.Command> alertManager;
    private final MatchingAlertService matchingAlertService;

    @Value("${sink-connector.alert.enabled:true}")
    private boolean alertEnabled;

    private CompletionStage<Done> consumerStream;

    /**
     * Start alert system when application is ready
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startAlertSystem() {
        if (!alertEnabled) {
            log.info("Alert system is DISABLED by configuration");
            return;
        }

        if (actorSystem == null || alertManager == null) {
            log.warn("ActorSystem or AlertManager is null, cannot start alert system");
            return;
        }

        log.info("============================================");
        log.info("Starting Alert System");
        log.info("============================================");

        try {
            // Start Kafka consumer for chart1m-data topic
            log.info("Starting Chart1m Kafka consumer...");
            consumerStream = matchingAlertService.startConsuming();

            log.info("Alert System started successfully!");
            log.info("  - ActorSystem: {}", actorSystem.name());
            log.info("  - AlertManager: Active");
            log.info("  - Kafka Consumer: Running");
            log.info("  - Outbox Relay: Scheduled");
            log.info("============================================");

        } catch (Exception e) {
            log.error("Failed to start Alert System: {}", e.getMessage(), e);
            throw new RuntimeException("Alert System startup failed", e);
        }
    }

    /**
     * Stop alert system gracefully
     */
    @PreDestroy
    public void stopAlertSystem() {
        if (!alertEnabled || actorSystem == null) {
            return;
        }

        log.info("============================================");
        log.info("Stopping Alert System");
        log.info("============================================");

        try {
            // Stop Kafka consumer
            if (consumerStream != null) {
                log.info("Stopping Chart1m Kafka consumer...");
                matchingAlertService.stopConsuming();
            }

            // ActorSystem shutdown is handled by AkkaConfig @PreDestroy

            log.info("Alert System stopped successfully");
            log.info("============================================");

        } catch (Exception e) {
            log.error("Error during Alert System shutdown: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if alert system is running
     */
    public boolean isRunning() {
        return alertEnabled && actorSystem != null && !actorSystem.whenTerminated().isCompleted();
    }

    /**
     * Get alert manager reference for external use
     */
    public ActorRef<AlertManagerActor.Command> getAlertManager() {
        return alertManager;
    }
}