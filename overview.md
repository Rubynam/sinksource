# Crypto Sink Connector - Logic Overview

## Table of Contents
1. [System Purpose](#system-purpose)
2. [Data Flow Architecture](#data-flow-architecture)
3. [Core Components](#core-components)
4. [Business Logic Deep Dive](#business-logic-deep-dive)
5. [Design Patterns](#design-patterns)
6. [Configuration Logic](#configuration-logic)
7. [Error Handling & Resilience](#error-handling--resilience)

---

## System Purpose

The Crypto Sink Connector is a **real-time data aggregation pipeline** that:
- Consumes cryptocurrency price tick data from Kafka
- Aggregates ticks into 1-minute OHLC (Open, High, Low, Close) candles using Apache Spark
- Persists candle data to ScyllaDB for time-series queries
- Supports multiple data sources (Binance, Huobi) with identical processing logic

**Key Business Value**: Transforms high-frequency price events (potentially thousands per second) into structured candle data for trading analysis, charting, and historical queries.

---

## Data Flow Architecture

### End-to-End Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. INGESTION LAYER                                                  │
│    Kafka Topic: inbound-symbol-source                               │
│    Message Format: JSON (PriceEventMessage)                         │
│    {symbol, source, bidPrice, askPrice, bidQty, askQty, timestamp}  │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 2. APPLICATION STARTUP                                              │
│    SinkConnectorRunner @EventListener(ApplicationReadyEvent)        │
│    - Creates Spark DataStreamReader (Kafka source)                 │
│    - Builds SparkStreamProfile with config (watermark, window, etc)│
│    - Submits OhlcProcessorCommand to ExecutorService (thread pool) │
│    - Optionally submits DebugSparkProcessorCommand if debug enabled│
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 3. COMMAND EXECUTION (Command Pattern)                             │
│    OhlcProcessorCommand.command(SparkStreamProfile)                 │
│    ├─ Step 1: OhlcStreamProcessor.read()                           │
│    │   └─ Returns Dataset<Row> with streaming aggregations         │
│    └─ Step 2: OhlcStreamProcessor.startWritten()                   │
│        └─ Configures foreachBatch writer with checkpointing        │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 4. SPARK STRUCTURED STREAMING PIPELINE                             │
│    OhlcStreamProcessor.read(Dataset<Row>, ...)                     │
│                                                                     │
│    A. Parse JSON from Kafka value                                  │
│       └─ from_json(col("json"), SchemaProcessor.getSchema())       │
│                                                                     │
│    B. Convert timestamp to event_timestamp                         │
│       └─ to_timestamp(col("timestamp"))                            │
│                                                                     │
│    C. Apply Watermark (handle late data)                           │
│       └─ withWatermark("event_timestamp", "30 seconds")            │
│                                                                     │
│    D. Window-based Grouping                                        │
│       └─ groupBy(                                                  │
│            window(col("event_timestamp"), "1 minute"),             │
│            col("source"),                                          │
│            col("symbol")                                           │
│          )                                                         │
│                                                                     │
│    E. OHLC Aggregation                                             │
│       └─ agg(                                                      │
│            first("bidPrice") as "open",                            │
│            max("bidPrice") as "high",                              │
│            min("askPrice") as "low",                               │
│            last("askPrice") as "close",                            │
│            sum("bidQty") as "volume",                              │
│            count("*") as "tick_count"                              │
│          )                                                         │
│                                                                     │
│    F. Final Column Selection                                       │
│       └─ select(source, symbol, timestamp, open, high, low,        │
│                 close, volume, tick_count, created_at)             │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 5. MICRO-BATCH PROCESSING                                          │
│    OhlcStreamProcessor.startWritten() - foreachBatch callback      │
│    Trigger: ProcessingTime("10 seconds")                           │
│                                                                     │
│    Per batch:                                                      │
│    ├─ Log batch ID and row count                                  │
│    ├─ Iterate through batchDF.toLocalIterator()                   │
│    ├─ DataRowTransformer.transform(Row) → Candle1mEntity          │
│    └─ Collect into List<Candle1mEntity>                           │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 6. PERSISTENCE LAYER                                               │
│    ScyllaWriteService.writeCandles1m(List<Candle1mEntity>)         │
│                                                                     │
│    A. RxJava Batching                                              │
│       └─ Observable.fromStream(data.stream())                      │
│           .buffer(100)  // Split into chunks of 100                │
│                                                                     │
│    B. Retry Logic (per 100-record batch)                           │
│       └─ retryTemplate.execute(context -> {                        │
│            candle1mRepository.saveAll(entities);                   │
│          })                                                        │
│       └─ Exponential backoff: 1s → 2s → 4s (max 3 attempts)       │
│                                                                     │
│    C. ScyllaDB Write                                               │
│       └─ CassandraRepository.saveAll()                             │
│           → INSERT INTO chart_m1 (...)                             │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ 7. CHECKPOINT & FAULT TOLERANCE                                    │
│    Spark Checkpoint Location: /tmp/spark-checkpoint/ohlc           │
│    - Stores stream state (watermark, aggregation state)            │
│    - Enables exactly-once processing semantics                     │
│    - Allows recovery from failures                                 │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. Presentation Layer

#### **SinkConnectorRunner** (`presentation/SinkConnectorRunner.java`)
**Responsibility**: Application lifecycle orchestration

**Logic**:
1. Waits for `ApplicationReadyEvent`
2. Checks if Spark is enabled (`sink-connector.spark.enabled`)
3. Creates Spark `DataStreamReader` configured for Kafka:
   - Bootstrap servers from config
   - Topic: `inbound-symbol-source`
   - Starting offsets: `latest`
   - Max offsets per trigger: 1000
4. Creates `ExecutorService` with 2 threads (custom ThreadFactory: "Spark-Stream-{id}")
5. Submits two concurrent stream processing jobs:
   - **OHLC Stream** (always): Production candle aggregation
   - **Debug Stream** (conditional): Console logging if `logging.stream.debug=true`
6. On shutdown (`@PreDestroy`): Stops SparkSession and ExecutorService

### 2. Application Layer (Command Pattern)

#### **Command Interface** (`application/command/Command.java`)
Generic command pattern: `Command<I, O>` with single method `O command(I input)`

#### **OhlcProcessorCommand** (`application/command/OhlcProcessorCommand.java`)
**Input**: `SparkStreamProfile` (contains DataStreamReader, watermark delay, window duration, write options)
**Output**: `Void`

**Logic**:
1. Calls `OhlcStreamProcessor.read(profile)` → returns `Dataset<Row>` with aggregations
2. Calls `OhlcStreamProcessor.startWritten(dataset, writeOptions, chartType)`
3. Catches `TimeoutException` and `StreamingQueryException`


### 3. Domain Layer - Business Logic

#### **SchemaProcessor** (Abstract Base Class)
**Responsibility**: Defines PriceEventMessage JSON schema for Spark

**Schema Definition**:
```java
StructType {
    symbol: StringType (required)
    source: StringType (required)
    bidPrice: DecimalType(18,8) (required)
    askPrice: DecimalType(18,8) (required)
    bidQty: DecimalType(18,8) (required)
    askQty: DecimalType(18,8) (required)
    timestamp: LongType (required)  // Unix timestamp in milliseconds
    eventId: StringType (required)
}
```

#### **OhlcStreamProcessor** (extends SchemaProcessor)
**Responsibility**: Core OHLC aggregation logic

**Key Methods**:

##### `read(SparkStreamProfile) → Dataset<Row>`
Convenience method that extracts profile properties and delegates to overloaded `read()`.

##### `read(Dataset<Row> kafkaStream, ChartType, watermarkDelay, windowDuration) → Dataset<Row>`
**This is the core aggregation logic**:

**Watermark Logic**:
- Late arrivals tolerated up to `watermark-delay` (default: 30 seconds)
- Events older than `(max_event_time - 30s)` are dropped
- Prevents unbounded state growth

**Window Logic**:
- Tumbling window (non-overlapping)
- Window size: 1 minute
- Window start time becomes the candle timestamp

##### `startWritten(Dataset<Row>, WriteOptions, ChartType)`
**Responsibility**: Configures Spark streaming writer

**Logic**:
```java
datasource.writeStream()
    .foreachBatch((batchDF, batchId) -> {
        // Convert Spark Row → Candle1mEntity
        List<Candle1mEntity> candles = new CopyOnWriteArrayList<>();
        Iterator<Row> iterator = batchDF.toLocalIterator();
        while (iterator.hasNext()) {
            candles.add(dataRowTransformer.transform(iterator.next()));
        }

        // Persist to ScyllaDB with retry
        scyllaWriteService.writeCandles1m(candles);
    })
    .outputMode("append")  // Only new candles, no updates
    .trigger(Trigger.ProcessingTime("10 seconds"))  // Micro-batch every 10s
    .option("checkpointLocation", "/tmp/spark-checkpoint/ohlc")
    .queryName("OhlcStream")
    .start()
    .awaitTermination();  // Blocks until stream stops
```

**Important**: Uses `CopyOnWriteArrayList` for thread-safe collection during iteration.

#### **DataLoggingStream** (extends SchemaProcessor)
**Responsibility**: Debug stream with enriched metadata

**Differences from OhlcStreamProcessor**:
1. **Enrichment**: Adds Kafka metadata columns:
   - `kafka_partition`: Which Kafka partition the message came from
   - `kafka_offset`: Offset within the partition
   - `kafka_timestamp`: Kafka broker timestamp
   - `omsServerId`: Generated as `"source-{partition}"`

2. **Output**: Writes to console instead of ScyllaDB
   ```java
   .writeStream()
       .format("console")
       .option("truncate", "true")
       .option("numRows", "10")
   ```

3. **No Aggregation**: Shows raw parsed events, not OHLC candles

---

### 4. Domain Layer - Transformation Logic

#### **Transformer Interface** (`domain/logic/Transformer.java`)
Generic transformation: `O transform(I input)`

#### **DataRowTransformer** (`domain/logic/batchprocessing/DataRowTransformer.java`)
**Responsibility**: Spark `Row` → `Candle1mEntity` conversion

**Transformation Logic**:
```java
Candle1mEntity.builder()
    .source(row.getAs("source"))                    // Direct string mapping
    .symbol(row.getAs("symbol"))                    // Direct string mapping
    .timestamp(convertToInstant(row.get(...)))      // Type-safe conversion
    .open(convertToBigDecimal(row.get(...)))        // Handles Scala/Java BigDecimal
    .high(convertToBigDecimal(row.get(...)))
    .low(convertToBigDecimal(row.get(...)))
    .close(convertToBigDecimal(row.get(...)))
    .volume(convertToBigDecimal(row.get(...)))
    .tickCount(row.getAs("tick_count"))             // Direct long mapping
    .createdAt(convertToInstant(row.get(...)))
    .build();
```

**Why Type Conversion is Needed**:
- Spark SQL uses Scala types internally (`scala.math.BigDecimal`)
- Java entities use `java.math.BigDecimal`
- `Formatter` utility handles cross-type conversions

---

### 5. Utility Logic

#### **Formatter** (`utils/Formatter.java`)
**Responsibility**: Type-safe conversions for Spark-to-Java interop

##### `convertToInstant(Object value)`
**Handles**:
- `java.sql.Timestamp` → `Instant`
- `Long` (epoch millis) → `Instant`
- `java.sql.Date` → `Instant`
- `null` → `Instant.now()` (fallback)

##### `convertToBigDecimal(Object value)`
**Handles**:
- `java.math.BigDecimal` → pass-through
- `scala.math.BigDecimal` → `.bigDecimal()` method
- `Double`, `Long`, `Integer` → `BigDecimal.valueOf()`
- Other types → `new BigDecimal(value.toString())`
- `null` → `BigDecimal.ZERO`

**Critical for**: Avoiding `ClassCastException` when Spark returns Scala types.

---

### 6. Persistence Layer

#### **ScyllaWriteService** (`domain/logic/batchprocessing/ScyllaWriteService.java`)
**Responsibility**: Batch writing with retry logic

**Logic Breakdown**:

```java
@Transactional  // Spring transaction management
public void writeCandles1m(List<Candle1mEntity> data) {
    Observable.<Candle1mEntity>fromStream(data.stream())
        .buffer(100)  // Split into chunks of 100 records
        .doOnNext(entities -> {
            retryTemplate.execute(context -> {
                log.info("Writing {} candles (attempt: {})",
                         entities.size(), context.getRetryCount() + 1);

                candle1mRepository.saveAll(entities);  // Cassandra batch insert

                log.info("Successfully wrote {} candles", entities.size());
                return null;
            });
        })
        .subscribe();  // RxJava terminal operation
}
```

**Why This Design**:
1. **RxJava Buffering**: Prevents memory exhaustion on large micro-batches
   - Spark might produce 1000s of candles per batch
   - Breaking into chunks of 100 prevents OOM

2. **Per-Chunk Retry**: Each 100-record batch retried independently
   - If batch 1 succeeds but batch 2 fails → batch 1 is committed
   - Reduces wasted work on partial failures

3. **Thread Safety**: `@Transactional` ensures Cassandra session consistency

#### **Candle1mRepository** (`infrastructure/repository/Candle1mRepository.java`)
Simple Spring Data Cassandra repository:
```java
interface Candle1mRepository extends CassandraRepository<Candle1mEntity, String>
```

**ScyllaDB Operations**:
- `saveAll()` → Batch INSERT statements
- Primary key: `(source, symbol, timestamp)` - ensures upsert behavior
- No explicit TTL in code (handled by ScyllaDB table schema)

---

## Design Patterns

### 1. **Command Pattern**
**Where**: `OhlcProcessorCommand`, `DebugSparkProcessorCommand`

**Why**:
- Decouples SinkConnectorRunner from processing logic
- Allows async execution via `ExecutorService.submit()`
- Enables future extensibility (new commands without changing runner)

### 2. **Template Method Pattern**
**Where**: `SchemaProcessor` (base class for `OhlcStreamProcessor`, `DataLoggingStream`)

**Why**:
- Shared schema definition across all processors
- Enforces consistent JSON parsing
- Child classes override `read()` for custom logic

### 3. **Strategy Pattern**
**Where**: Dual-mode processing (Spark vs Kafka Listener)

**Why**:
- Runtime selection of processing strategy via config
- Same business logic, different execution engines
- Allows A/B testing or gradual migration

### 4. **Builder Pattern**
**Where**: All domain objects (`SparkStreamProfile`, `WriteOptions`, `Candle1mEntity`, etc.)

**Why**:
- Immutable data objects (Lombok `@Builder`)
- Clear construction with many optional parameters

### 5. **Transformer Pattern**
**Where**: `DataRowTransformer implements Transformer<Row, Candle1mEntity>`

**Why**:
- Separation of transformation logic from processing logic
- Reusable transformers (could transform to different entity types)
- Testable in isolation

---


## Error Handling & Resilience

### 1. **Late Data Handling (Watermark)**
**Problem**: Network delays cause events to arrive out-of-order

**Solution**:
```java
.withWatermark("event_timestamp", "30 seconds")
```
- Events arriving < 30s late: Included in aggregation
- Events arriving > 30s late: Dropped (logged by Spark)
- State cleanup: After watermark passes, old window state is deleted

### 2. **Checkpoint-Based Recovery**
**Problem**: Application crash during processing

**Solution**:
- Spark writes state to `/tmp/spark-checkpoint/ohlc` after each micro-batch
- On restart: Reads checkpoint, resumes from last committed offset
- Guarantees: Exactly-once processing (each Kafka message processed once)

### 3. **Retry on Transient Failures**
**Problem**: ScyllaDB temporary unavailability (network blip, coordinator failover)

**Solution**:
- `RetryTemplate` catches exceptions from `saveAll()`
- Exponential backoff prevents overwhelming recovering nodes
- After 3 attempts: Logs error, Spark stream fails (triggers checkpoint recovery)

### 4. **RxJava Buffering**
**Problem**: Large micro-batch causes memory pressure

**Solution**:
```java
Observable.buffer(100)
```
- Backpressure handling: Processes 100 records at a time
- Prevents `OutOfMemoryError` on large windows (e.g., market open surge)

### 5. **Thread Isolation**
**Problem**: Stream failure should not crash entire application

**Solution**:
- Each stream runs in separate thread (`ExecutorService` thread pool)
- OHLC stream failure: Does not affect Debug stream
- Uncaught exceptions: Logged but do not propagate to Spring container

### 6. **Data Quality - Type Safety**
**Problem**: Spark/Scala type mismatches cause runtime errors

**Solution**:
- `Formatter.convertToBigDecimal()` handles 6 different numeric types
- Fallbacks: `null` → sensible defaults (`BigDecimal.ZERO`, `Instant.now()`)
- No `ClassCastException` propagation to persistence layer

---

## State Management

### Spark Streaming State

**What is stored**:
1. **Watermark timestamp**: Latest event timestamp seen (for late data filtering)
2. **Window aggregations**: Partial OHLC state for open windows
   - Example: At 10:00:45, window 10:00:00-10:01:00 has:
     - Current open: first bid price seen
     - Current high: max bid price so far
     - Current low: min ask price so far
     - Partial volume: sum of bid quantities so far
3. **Kafka offsets**: Last committed offset per partition

**State Size Estimation**:
- Per window per (source, symbol): ~200 bytes
- With 100 symbols, 2 sources, 1-minute windows: ~40 KB active state
- State TTL: After watermark + window duration (1.5 minutes total)

**Checkpoint Format**:
```
/tmp/spark-checkpoint/ohlc/
├── commits/           # Committed batch IDs
├── metadata           # Query configuration
├── offsets/           # Kafka offset logs
├── sources/           # Kafka source configuration
└── state/             # RocksDB state store
    └── 0/             # Partition 0
        └── 1/         # Version 1
            ├── *.sst  # RocksDB sorted string tables
            └── *.log  # RocksDB write-ahead logs
```

---

## Performance Characteristics

### Throughput
- **Kafka consumption**: 1000 messages/partition/trigger (10s interval)
- **Effective rate**: 100 msg/sec/partition
- **Aggregation**: In-memory (Spark SQL Catalyst optimizer)
- **ScyllaDB write**: 100 records/batch, 10 batches/sec = 1000 writes/sec

### Latency
- **End-to-end**: ~40 seconds (worst case)
  - Watermark delay: 30s
  - Micro-batch interval: 10s
- **Best case**: 10 seconds (event arrives just before batch trigger)

### Resource Usage
- **Memory**: 2GB driver + 4GB executor = 6GB total
- **CPU**: 2 executor cores (local[*] uses all available)
- **Disk**: Checkpoint grows ~10 MB/hour (cleanup needed)



## Summary

This system demonstrates **stream processing best practices**:

1. **Separation of Concerns**: Clear layers (presentation → command → domain → infrastructure)
2. **Fault Tolerance**: Checkpointing, retry logic, watermarking
3. **Scalability**: Spark's distributed processing (currently local, easily scales to cluster)
4. **Flexibility**: Dual-mode processing, extensible command pattern
5. **Type Safety**: Careful handling of Spark/Java/Scala type conversions
6. **Resilience**: Graceful degradation (late data drops, retry limits)

**Core Business Logic** resides in:
- `OhlcStreamProcessor.read()`: OHLC aggregation algorithm
- `ScyllaWriteService`: Batch + retry persistence strategy
- `DataRowTransformer`: Type-safe conversions

Everything else is **infrastructure support** for this core logic.
