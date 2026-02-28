package com.example.sinkconnect.domain.logic.alert.service;

import com.example.sinkconnect.domain.logic.alert.OutboxStatus;
import com.example.sinkconnect.infrastructure.entity.OutboxEntity;
import com.example.sinkconnect.infrastructure.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * OutboxRelayService - Polls outbox table and sends notifications
 *
 * Responsibilities:
 * 1. Poll alert_outbox table for PENDING messages (every 5 seconds)
 * 2. Send notifications to external service (SNS, Email, Kafka, etc.)
 * 3. Update outbox status to SENT or FAILED
 * 4. Implement retry logic (max 3 attempts)
 * 5. Clean up old processed messages
 *
 * This completes the Outbox Pattern by handling async delivery
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxRepository outboxRepository;
    private final NotificationService notificationService;

    @Value("${sink-connector.alert.outbox-batch-size:100}")
    private int batchSize;

    @Value("${sink-connector.alert.outbox-max-retries:3}")
    private int maxRetries;

    /**
     * Scheduled task to poll and process pending outbox messages
     * Runs every 5 seconds by default
     */
    @Scheduled(fixedDelayString = "${sink-connector.alert.outbox-poll-interval-ms:5000}")
    public void processPendingMessages() {
        try {
            // Fetch pending messages from outbox
            List<OutboxEntity> pendingMessages = outboxRepository.findPendingMessages(batchSize);

            if (pendingMessages.isEmpty()) {
                log.debug("No pending outbox messages to process");
                return;
            }

            log.info("Processing {} pending outbox messages", pendingMessages.size());

            // Process each message
            pendingMessages.forEach(this::processMessage);

        } catch (Exception e) {
            log.error("Error processing pending outbox messages: {}", e.getMessage(), e);
        }
    }

    /**
     * Process a single outbox message
     */
    private void processMessage(OutboxEntity message) {
        try {
            // Check retry limit
            if (message.getRetryCount() >= maxRetries) {
                log.warn("Message {} has exceeded max retries ({}), marking as FAILED",
                        message.getOutboxId(), maxRetries);

                updateMessageStatus(
                        message,
                        OutboxStatus.FAILED,
                        "Exceeded maximum retry attempts (" + maxRetries + ")"
                );
                return;
            }

            // Send notification via external service
            boolean success = sendNotification(message);

            if (success) {
                // Mark as SENT
                updateMessageStatus(message, OutboxStatus.SENT, null);
                log.info("Successfully sent notification for alert {}", message.getAlertId());
            } else {
                // Increment retry count and keep as PENDING
                incrementRetryCount(message);
                log.warn("Failed to send notification for alert {}, will retry (attempt {}/{})",
                        message.getAlertId(), message.getRetryCount() + 1, maxRetries);
            }

        } catch (Exception e) {
            log.error("Error processing outbox message {}: {}",
                    message.getOutboxId(), e.getMessage(), e);

            // Increment retry count
            incrementRetryCount(message);
        }
    }

    /**
     * Send notification via external service (SNS, Email, Kafka, etc.)
     */
    private boolean sendNotification(OutboxEntity message) {
        try {
            // Delegate to NotificationService based on message type
            return notificationService.sendNotification(
                    message.getAlertId(),
                    message.getSymbol(),
                    message.getSource(),
                    message.getMessageType(),
                    message.getPayload()
            );

        } catch (Exception e) {
            log.error("Failed to send notification for message {}: {}",
                    message.getOutboxId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Update outbox message status
     * Note: In Cassandra, we can't update partition key, so we delete + insert
     */
    private void updateMessageStatus(OutboxEntity message, OutboxStatus newStatus, String errorMessage) {
        try {
            // Delete old entity (with old status partition key)
            outboxRepository.delete(message);

            // Create new entity with updated status
            OutboxEntity updatedMessage = OutboxEntity.builder()
                    .outboxId(message.getOutboxId())
                    .alertId(message.getAlertId())
                    .symbol(message.getSymbol())
                    .source(message.getSource())
                    .messageType(message.getMessageType())
                    .payload(message.getPayload())
                    .status(newStatus.name())
                    .createdAt(message.getCreatedAt())
                    .processedAt(Instant.now())
                    .retryCount(message.getRetryCount())
                    .errorMessage(errorMessage)
                    .build();

            outboxRepository.save(updatedMessage);

            log.debug("Updated outbox message {} status to {}", message.getOutboxId(), newStatus);

        } catch (Exception e) {
            log.error("Failed to update outbox message {} status: {}",
                    message.getOutboxId(), e.getMessage(), e);
        }
    }

    /**
     * Increment retry count for failed message
     */
    private void incrementRetryCount(OutboxEntity message) {
        try {
            // Delete old entity
            outboxRepository.delete(message);

            // Create new entity with incremented retry count
            OutboxEntity updatedMessage = OutboxEntity.builder()
                    .outboxId(message.getOutboxId())
                    .alertId(message.getAlertId())
                    .symbol(message.getSymbol())
                    .source(message.getSource())
                    .messageType(message.getMessageType())
                    .payload(message.getPayload())
                    .status(OutboxStatus.PENDING.name())  // Keep as PENDING for retry
                    .createdAt(message.getCreatedAt())
                    .processedAt(null)
                    .retryCount(message.getRetryCount() + 1)
                    .errorMessage("Retry attempt " + (message.getRetryCount() + 1))
                    .build();

            outboxRepository.save(updatedMessage);

        } catch (Exception e) {
            log.error("Failed to increment retry count for message {}: {}",
                    message.getOutboxId(), e.getMessage(), e);
        }
    }

    /**
     * Clean up old processed messages (SENT/FAILED older than retention period)
     * This prevents unbounded outbox table growth
     */
    @Scheduled(cron = "${sink-connector.alert.outbox-cleanup-cron:0 0 2 * * ?}")  // Daily at 2 AM
    public void cleanupOldMessages() {
        log.info("Starting outbox cleanup (old SENT/FAILED messages)");

        // Note: Cassandra TTL handles automatic cleanup
        // This method is a placeholder for custom cleanup logic if needed
        // The alert_outbox table has default_time_to_live = 604800 (7 days)

        log.info("Outbox cleanup completed (handled by Cassandra TTL)");
    }
}