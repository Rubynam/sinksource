# Alert System Implementation Status

## Completed Components

### 1. Dependencies (✅ DONE)
- Added Akka Actor Typed 2.8.5
- Added Akka Persistence Typed 2.8.5
- Added Akka Stream Kafka 4.0.2
- Added Akka Persistence Cassandra 1.1.1
- Added Akka Cluster Sharding Typed 2.8.5
- Added Akka Serialization Jackson 2.8.5

### 2. Kafka Publisher for Chart1m Data (✅ DONE)
**Files Created:**
- `KafkaProducerConfig.java` - Kafka producer configuration with idempotence
- `CandlePublishService.java` - Service to publish Chart1m candles to Kafka topic `chart1m-data`

**Integration:**
- Modified `OhlcStreamProcessor.java` to publish candles after ScyllaDB persistence
- Topic: `chart1m-data` (configurable via `sink-connector.topics.chart1m-output`)

### 3. ScyllaDB Schema (✅ DONE)
**File:** `src/main/resources/schema/alert-schema.cql`

**Tables Created:**
- `price_alerts` - Alert configurations with hit count tracking
- `symbol_status` - Symbol enable/disable tracking
- `akka_journal` - Akka Persistence event journal
- `akka_snapshot` - Akka Persistence snapshots
- `akka_metadata` - Akka Persistence metadata
- `alert_outbox` - Outbox pattern for notifications (partitioned by status)
- `alert_trigger_history` - Alert trigger analytics

### 4. Domain Models (✅ DONE)
**Files Created:**
- `AlertCondition.java` - ABOVE, BELOW, CROSS_ABOVE, CROSS_BELOW
- `AlertStatus.java` - ENABLED, DISABLED, TRIGGERED, EXPIRED
- `SymbolStatus.java` - ENABLED, DISABLED
- `PriceAlert.java` - Alert domain model with business logic
- `OutboxMessageType.java` - Message types for outbox
- `OutboxStatus.java` - PENDING, PROCESSING, SENT, FAILED
- `OutboxMessage.java` - Outbox domain model

### 5. Event Sourcing (✅ DONE)
**Files Created:**
- `AlertEvent.java` - Base event interface with Jackson serialization
- `AlertCreated.java` - Alert creation event
- `AlertTriggered.java` - Price match event
- `AlertDisabled.java` - Alert disable event
- `AlertExpired.java` - Hit limit reached event
- `AlertCommand.java` - Command interface (CreateAlert, CheckPrice, UpdateSymbolStatus, DisableAlert, GetState)
- `AlertState.java` - Immutable event-sourced state with business logic

## Remaining Components (TODO)

### 6. AlertActor Implementation (🔄 IN PROGRESS)
**What Needs to Be Done:**
1. Create `AlertActor.java` - EventSourcedBehavior implementation
   - Implements command handlers
   - Implements event handlers (state recovery)
   - Integrates with Outbox repository
   - Price matching logic
   - Symbol status checking
   - Hit count limit enforcement

**Key Business Logic:**
```java
// Pseudocode structure
class AlertActor extends EventSourcedBehavior<AlertCommand, AlertEvent, AlertState> {

    // Command Handler
    CommandHandler<CheckPrice> {
        if (!state.isActive()) {
            return Effect().none(); // Drop if disabled or symbol disabled
        }

        if (state.matchesCondition(price, previousPrice)) {
            if (state.hasReachedLimit()) {
                return Effect()
                    .persist(AlertExpired)
                    .thenRun(() -> saveToOutbox(ALERT_LIMIT_REACHED));
            } else {
                return Effect()
                    .persist(AlertTriggered)
                    .thenRun(() -> saveToOutbox(ALERT_TRIGGERED));
            }
        }

        return Effect().none();
    }

    // Event Handler (for recovery)
    EventHandler<AlertTriggered> {
        return state.applyTriggered(event);
    }
}
```

### 7. AlertManagerActor (⏳ PENDING)
**What Needs to Be Done:**
1. Create `AlertManagerActor.java` - Cluster sharding coordinator
   - Manages AlertActor lifecycles
   - Routes messages to correct alert by alertId
   - Handles alert registration/deregistration
   - Broadcasts symbol status updates

### 8. Kafka Consumer with Akka Streams (⏳ PENDING)
**What Needs to Be Done:**
1. Create `Chart1mConsumerService.java`
   - Akka Streams Kafka consumer for `chart1m-data` topic
   - Implements backpressure (max in-flight messages)
   - Groups messages by (source, symbol)
   - Forwards to AlertManagerActor

**Flow:**
```
Kafka (chart1m-data)
    → Akka Streams Consumer (with backpressure)
    → Group by source+symbol
    → Fetch active alerts for symbol
    → Send CheckPrice commands to AlertActors
```

### 9. Outbox Pattern Implementation (⏳ PENDING)
**What Needs to Be Done:**
1. Create `OutboxEntity.java` - ScyllaDB entity
2. Create `OutboxRepository.java` - Spring Data Cassandra repository
3. Create `OutboxService.java` - Transactional service
   - `saveOutboxMessage(OutboxMessage)` - Atomic save with alert state
   - Uses same Cassandra session as Akka Persistence

### 10. Outbox Relay Service (⏳ PENDING)
**What Needs to Be Done:**
1. Create `OutboxRelayService.java`
   - Polls `alert_outbox` table (status=PENDING)
   - Sends notifications to external service (SNS, Email, etc.)
   - Updates status to SENT or FAILED
   - Implements retry logic (max 3 attempts)
   - Scheduled task (@Scheduled every 5 seconds)

### 11. Akka Configuration (⏳ PENDING)
**What Needs to Be Done:**
1. Create `application-akka.conf` - Akka configuration
   - Persistence plugin (Cassandra)
   - Serialization (Jackson)
   - Cluster sharding settings

2. Update `application.yaml` - Add Akka properties
   ```yaml
   alert:
     enabled: true
     kafka-topic: chart1m-data
     max-hit-count: 10
     outbox-poll-interval: 5s
   ```

3. Create `AkkaConfig.java` - Spring configuration
   - Creates ActorSystem bean
   - Initializes AlertManagerActor
   - Starts Kafka consumer streams

### 12. Integration Wiring (⏳ PENDING)
**What Needs to Be Done:**
1. Modify `SinkConnectorRunner.java`
   - Start AlertManagerActor on ApplicationReadyEvent
   - Start Chart1m Kafka consumer
   - Graceful shutdown on @PreDestroy

2. Create REST endpoints (optional):
   - POST /alerts - Create alert
   - GET /alerts/{alertId} - Get alert state
   - PUT /alerts/{alertId}/disable - Disable alert
   - PUT /symbols/{source}/{symbol}/status - Update symbol status

## Testing Requirements

### Unit Tests Needed:
1. `AlertStateTest` - State transitions and matching logic
2. `AlertActorTest` - Command/Event handling with TestKit
3. `OutboxServiceTest` - Transactional outbox saves

### Integration Tests Needed:
1. End-to-end flow: Kafka → AlertActor → Outbox → Relay
2. Event sourcing recovery test
3. Backpressure handling test

## Configuration Example

### application.yaml
```yaml
sink-connector:
  topics:
    chart1m-output: chart1m-data

  alert:
    enabled: true
    max-hit-count: 10
    outbox-poll-interval-ms: 5000
    backpressure-buffer-size: 1000
```

### Environment Variables
```bash
ALERT_ENABLED=true
CHART1M_TOPIC=chart1m-data
```

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────────┐
│ EXISTING: Spark → ScyllaDB (chart_m1)                       │
│           Spark → Kafka (chart1m-data) ✅ NEW               │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ Kafka Consumer (Akka Streams) - Backpressure                │
│ - Consumes chart1m-data topic                                │
│ - Groups by (source, symbol)                                 │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ AlertManagerActor (Cluster Sharding)                         │
│ - Routes to AlertActor by alertId                            │
│ - Manages actor lifecycle                                    │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ AlertActor (Event Sourced)                                   │
│ - State: hitCount, status, symbolStatus                      │
│ - Commands: CheckPrice, UpdateSymbolStatus                   │
│ - Events: AlertTriggered, AlertExpired                       │
│                                                              │
│ Business Logic:                                              │
│ 1. Check if symbol enabled (symbolStatus == ENABLED)        │
│ 2. Match price condition                                    │
│ 3. Check hit count < 10                                      │
│ 4. Persist event → Update state                             │
│ 5. Save to Outbox (transactional)                           │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ Outbox Pattern (ScyllaDB)                                    │
│ - Atomic write with Akka Persistence journal                 │
│ - Partitioned by status (PENDING, SENT, FAILED)             │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ OutboxRelayService (Scheduled @5s)                           │
│ - Polls PENDING outbox messages                             │
│ - Sends to notification service (SNS, Email, etc.)          │
│ - Updates status to SENT/FAILED                             │
│ - Implements retry (max 3 attempts)                         │
└──────────────────────────────────────────────────────────────┘
```

## Next Steps

1. **PRIORITY 1**: Implement AlertActor (core business logic)
2. **PRIORITY 2**: Implement OutboxRepository and OutboxService
3. **PRIORITY 3**: Implement AlertManagerActor
4. **PRIORITY 4**: Implement Kafka consumer with Akka Streams
5. **PRIORITY 5**: Implement OutboxRelayService
6. **PRIORITY 6**: Add Akka configuration
7. **PRIORITY 7**: Wire everything in SinkConnectorRunner

## Estimated Remaining Work
- AlertActor: ~200 lines
- OutboxRepository/Service: ~100 lines
- AlertManagerActor: ~150 lines
- Kafka Consumer: ~150 lines
- OutboxRelayService: ~100 lines
- Configuration: ~100 lines
- Integration: ~50 lines

**Total: ~850 lines remaining**

## Dependencies Status
✅ All Maven dependencies added to build.gradle
✅ ScyllaDB schema defined
✅ Domain models complete
✅ Event sourcing foundation complete