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
   
### 6. Using redis for distributed locking and replace hashmap at Chart1mConsumerService line 68 previousPrices.
- Technical requitements: You must use redis template to communicate redis.
- Create a docker file to install standalone redis.
- Create a CacheService to
- The key pattern in redis must be `PREVIOOUS_PRICE:<SYMBOL>`
- For the Memory Efficiency. Redis optimizes small hashes (typically under 512 entries) using a ziplist or listpack. This uses significantly less RAM than millions of individual top-level keys because it eliminates the per-key metadata overhead.
### 7. [BUG] application-akka.conf can not pass Authentication user password and keyname space.
- Detail 
```text
	Suppressed: com.datastax.oss.driver.api.core.auth.AuthenticationException: Authentication error on node /127.0.0.1:9042: Node /127.0.0.1:9042 requires authentication (org.apache.cassandra.auth.PasswordAuthenticator), but no authenticator configured
		at com.datastax.oss.driver.internal.core.channel.ProtocolInitHandler$InitRequest.lambda$buildAuthenticator$5(ProtocolInitHandler.java:368) ~[java-driver-core-4.14.1.jar:na]
		at java.base/java.util.Optional.orElseThrow(Optional.java:403) ~[na:na]
		at com.datastax.oss.driver.internal.core.channel.ProtocolInitHandler$InitRequest.buildAuthenticator(ProtocolInitHandler.java:364) ~[java-driver-core-4.14.1.jar:na]
		at com.datastax.oss.driver.internal.core.channel.ProtocolInitHandler$InitRequest.onResponse(ProtocolInitHandler.java:216) ~[java-driver-core-4.14.1.jar:na]
		at com.datastax.oss.driver.internal.core.channel.ChannelHandlerRequest.onResponse(ChannelHandlerRequest.java:94) ~[java-driver-core-4.14.1.jar:na]
		at com.datastax.oss.driver.internal.core.channel.InFlightHandler.channelRead(InFlightHandler.java:257) ~[java-driver-core-4.14.1.jar:na]
		at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:442) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:420) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.AbstractChannelHandlerContext.fireChannelRead(AbstractChannelHandlerContext.java:412) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.handler.codec.ByteToMessageDecoder.fireChannelRead(ByteToMessageDecoder.java:361) ~[netty-codec-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.handler.codec.ByteToMessageDecoder.channelRead(ByteToMessageDecoder.java:325) ~[netty-codec-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:444) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:420) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.AbstractChannelHandlerContext.fireChannelRead(AbstractChannelHandlerContext.java:412) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.DefaultChannelPipeline$HeadContext.channelRead(DefaultChannelPipeline.java:1357) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:440) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:420) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.DefaultChannelPipeline.fireChannelRead(DefaultChannelPipeline.java:868) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe.read(AbstractNioByteChannel.java:166) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.nio.NioEventLoop.processSelectedKey(NioEventLoop.java:796) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.nio.NioEventLoop.processSelectedKeysOptimized(NioEventLoop.java:732) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.nio.NioEventLoop.processSelectedKeys(NioEventLoop.java:658) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:562) ~[netty-transport-4.1.130.Final.jar:4.1.130.Final]
		... 4 common frames omitted
```

### 8. [BUG] unconfigure table all_persistence_ids and failed to persist event
```text
com.datastax.oss.driver.api.core.servererrors.InvalidQueryException: unconfigured table all_persistence_ids
Failed to persist event type [akka.cluster.sharding.internal.EventSourcedRememberEntitiesCoordinatorStore$MigrationMarker$] with sequence number [1] for persistenceId [/sharding/AlertCoordinator].
```

### 9 [BUG] Performance issue
Context
```sql

SELECT * FROM market.price_alerts WHERE symbol = 'BTCUSDT' AND source = 'BINANCE' AND status = 'ENABLED' AND target_price >= 80000 ALLOW FILTERING
```
Query plainer can not find the suitable index for this. Because you don't create it.
Solution:
- Create file index-magement.sql
- You create a btree index included (symbol, source, status, target_price desc)
- Optimize query get the latest prices (follow updated_at)

### 10 [BUG] unknow identifier timebucket

log
```text
2026-03-01T15:14:26.574+07:00 ERROR 73446 --- [sink-connect] [lt-dispatcher-3] tSourcedRememberEntitiesCoordinatorStore : Failed to persist event type [akka.cluster.sharding.internal.EventSourcedRememberEntitiesCoordinatorStore$MigrationMarker$] with sequence number [1] for persistenceId [/sharding/AlertCoordinator].

com.datastax.oss.driver.api.core.servererrors.InvalidQueryException: Unknown identifier timebucket

2026-03-01T15:14:26.575+07:00 ERROR 73446 --- [sink-connect] [lt-dispatcher-3] a.c.sharding.DDataShardCoordinator       : Alert: The ShardCoordinator stopping because the remember entities store stopped
```

Context:
 - When i start AKAK actor and EntitiesCoordinatorStore , the properites timebucket not found, you need to review the whole project and check this config

### 12 [BUG] Actor AKKA sharing fail
logs
```text
java.lang.ClassCastException: class idea.debugger.rt.GeneratedEvaluationClass$1 cannot be cast to class akka.cluster.sharding.typed.internal.EntityTypeKeyImpl (idea.debugger.rt.GeneratedEvaluationClass$1 is in unnamed module of loader java.security.SecureClassLoader @2669bc63; akka.cluster.sharding.typed.internal.EntityTypeKeyImpl is in unnamed module of loader 'app')
	at akka.cluster.sharding.typed.internal.ClusterShardingImpl.entityRefFor(ClusterShardingImpl.scala:269)
	at idea.debugger.rt.GeneratedEvaluationClass.invoke(GeneratedEvaluationClass.java:11)
	at java.base/java.lang.invoke.MethodHandle.invokeWithArguments(MethodHandle.java:732)
	at com.intellij.rt.debugger.MethodInvoker.invokeInternal(MethodInvoker.java:223)
	at com.intellij.rt.debugger.MethodInvoker.invoke1(MethodInvoker.java:35)
	at com.example.sinkconnect.domain.logic.alert.actor.AlertManagerActor.lambda$onCheckPriceForSymbol$3(AlertManagerActor.java:305)
	at java.base/java.util.HashMap$KeySet.forEach(HashMap.java:1008)
	at com.example.sinkconnect.domain.logic.alert.actor.AlertManagerActor.onCheckPriceForSymbol(AlertManagerActor.java:304)
```
- Action: analyze this erro, find rootcause and update code

### Task 13 [BUG] Alert could not trigger when price hit target
logs
```text
Forwarded price check for Binance-BTCUSDT: current=64757.80000000, previous=64752.0, alerts=8
2026-03-01T15:59:23.146+07:00  INFO 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.l.alert.actor.AlertManagerActor  : Registered alert 17c55fe5-c28b-4993-b48f-e39c4f874df3 for symbol Binance-BTCUSDT (total alerts for symbol: 1)
2026-03-01T15:59:23.146+07:00  INFO 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.l.alert.actor.AlertManagerActor  : Registered alert 50798aaa-c325-4377-9e07-ee6afc8f6552 for symbol Binance-BTCUSDT (total alerts for symbol: 2)
2026-03-01T15:59:23.146+07:00  INFO 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.l.alert.actor.AlertManagerActor  : Registered alert c09c7483-b4ea-4f5b-a050-8af3c41ff3ec for symbol Binance-BTCUSDT (total alerts for symbol: 3)
2026-03-01T15:59:23.146+07:00  INFO 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.l.alert.actor.AlertManagerActor  : Registered alert cd7fb2da-2158-4806-b8cf-35232196f947 for symbol Binance-BTCUSDT (total alerts for symbol: 4)
2026-03-01T15:59:23.146+07:00  INFO 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.l.alert.actor.AlertManagerActor  : Registered alert 9f2a7bb7-bf5d-478f-93ed-001834294946 for symbol Binance-BTCUSDT (total alerts for symbol: 5)
2026-03-01T15:59:23.146+07:00  INFO 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.l.alert.actor.AlertManagerActor  : Registered alert a690e549-6d12-4091-835c-443941a4ab9e for symbol Binance-BTCUSDT (total alerts for symbol: 6)
2026-03-01T15:59:23.146+07:00  INFO 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.l.alert.actor.AlertManagerActor  : Registered alert 44aefa63-629c-4c2a-910e-6a2b200fd69f for symbol Binance-BTCUSDT (total alerts for symbol: 7)
2026-03-01T15:59:23.146+07:00  INFO 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.l.alert.actor.AlertManagerActor  : Registered alert 15d55224-8a39-4504-a69e-461b46a1333f for symbol Binance-BTCUSDT (total alerts for symbol: 8)
2026-03-01T15:59:23.150+07:00  WARN 92330 --- [sink-connect] [lt-dispatcher-3] c.e.s.d.logic.alert.actor.AlertActor     : Alert c09c7483-b4ea-4f5b-a050-8af3c41ff3ec not initialized, ignoring price check
2026-03-01T15:59:23.150+07:00  WARN 92330 --- [sink-connect] [t-dispatcher-24] c.e.s.d.logic.alert.actor.AlertActor     : Alert 50798aaa-c325-4377-9e07-ee6afc8f6552 not initialized, ignoring price check
2026-03-01T15:59:23.150+07:00  WARN 92330 --- [sink-connect] [t-dispatcher-29] c.e.s.d.logic.alert.actor.AlertActor     : Alert 44aefa63-629c-4c2a-910e-6a2b200fd69f not initialized, ignoring price check
2026-03-01T15:59:23.150+07:00  WARN 92330 --- [sink-connect] [t-dispatcher-15] c.e.s.d.logic.alert.actor.AlertActor     : Alert cd7fb2da-2158-4806-b8cf-35232196f947 not initialized, ignoring price check
2026-03-01T15:59:23.150+07:00  WARN 92330 --- [sink-connect] [t-dispatcher-28] c.e.s.d.logic.alert.actor.AlertActor     : Alert 17c55fe5-c28b-4993-b48f-e39c4f874df3 not initialized, ignoring price check
2026-03-01T15:59:23.150+07:00  WARN 92330 --- [sink-connect] [lt-dispatcher-5] c.e.s.d.logic.alert.actor.AlertActor     : Alert 15d55224-8a39-4504-a69e-461b46a1333f not initialized, ignoring price check
2026-03-01T15:59:23.150+07:00  WARN 92330 --- [sink-connect] [t-dispatcher-26] c.e.s.d.logic.alert.actor.AlertActor     : Alert a690e549-6d12-4091-835c-443941a4ab9e not initialized, ignoring price check
2026-03-01T15:59:23.150+07:00  WARN 92330 --- [sink-connect] [t-dispatcher-25] c.e.s.d.logic.alert.actor.AlertActor     : Alert 9f2a7bb7-bf5d-478f-93ed-001834294946 not initialized, ignoring price check
```
- The problem is that, the first step, actor register alert price to local cache. Second step, i witness, AlertActor could not found the alert id and throw log not initialized, ignoring price check DUE TO the alert ID in cmd already have.