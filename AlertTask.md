## User:
You are a Software Engineer working in a Fintech/Trading environment. I need to use a matching engine algorithm to trigger price alerts when consuming price data from Kafka. I want to handle this asynchronously using the Akka Actor Model.
Please pay attention to these requirements:
When a price hits an alert, check if the hit count has exceeded a limit of 10.
If the price hits the alert but the symbol has just been disabled, do not send a notification.
For sending notifications, I want to use the Outbox Pattern to avoid dual writes.

## System Architecture
We’ll split the system into specific Actors to ensure isolation and scalability:
- After Spark process chart 1m data success, you need to create a topic to public chart1m data for next process aka Alert price
- Kafka Consumer Actor: Responsible for polling price data from Kafka and forwarding it to the Manager. Check overview 

- Alert Manager Actor: Manages the lifecycle of individual Alert Actors (sharded by Symbol or AlertID).

- Alert Actor (Individual): Each Actor represents a specific Price Alert. It maintains internal state: hitCount and status (enabled/disabled).

- Outbox Actor/Repository: Handles persisting notifications to the Outbox table in the same transaction as the state update.
### 1. Processing Logic & WorkflowWhen a Price Event flows from Kafka into the system
- Step 1: Matching Logic at the Alert ActorUpon receiving a new price, the Actor performs the following checkpoints:Check Symbol Status: The Actor should have the symbol status cached or receive a signal if it's disabled. If symbol_status == DISABLED, drop the event immediately.Price Matching: Verify if the current price satisfies the alert condition (e.g., $Price \ge Target$).Check Limit: If the price matches, check the hitCount state. If hitCount >= 10, cease processing (or terminate the actor to free up resources).Step 2: Implementing the Outbox PatternTo prevent Dual Writes (e.g., updating the DB but failing to push to a Message Queue/SNS), we use the Outbox Pattern:Atomic Transaction: Instead of sending the notification directly, the Actor requests the Database to save a record into an Outbox table and increment the hitCount within the same local transaction.Relay Service: A separate worker (or Akka Projection) polls this Outbox table to push messages to the Notification Service
- Step 2: State Recovery:  You should use Akka Persistence to journal events. (create a journal event in scylla db). Upon restart, the Actor will replay events to restore the exact hitCount.
- Step 3: Backpressure: Kafka can pour data in very fast. Use Akka Streams for the Kafka consumer to implement backpressure, preventing the Actor Mailbox from overflowing.
- Step 4: Outbox Cleanup: The Outbox table grows quickly. You'll need a mechanism to purge "processed" records to maintain Database performance.


### 
### 2. AlertActor Implementation (🔄 IN PROGRESS)
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

### 3. AlertManagerActor (⏳ PENDING)
**What Needs to Be Done:**
1. Create `AlertManagerActor.java` - Cluster sharding coordinator
    - Manages AlertActor lifecycles
    - Routes messages to correct alert by alertId
    - Handles alert registration/deregistration
    - Broadcasts symbol status updates

### 4. Kafka Consumer with Akka Streams (⏳ PENDING)
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

### 5. Akka Configuration (⏳ PENDING)
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
