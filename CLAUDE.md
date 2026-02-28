# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Spring Boot application that implements a Kafka sink connector for cryptocurrency price data. The system consumes price events from Kafka topics (Binance and Huobi exchanges), processes them using Apache Spark Structured Streaming, and stores aggregated 1-minute OHLC candle data in ScyllaDB.

## Build & Run Commands

### Build
```bash
./gradlew build
```

### Run Application
```bash
# Default mode (Spark Streaming enabled)
./gradlew bootRun

# Disable Spark Streaming (Kafka Listener mode only)
SPARK_ENABLED=false ./gradlenv bootRun
```

### Run Tests
```bash
./gradlew test
```

### Clean Build
```bash
./gradlew clean build
```

## Architecture

### Dual-Mode Processing
The application supports two processing modes controlled by `sink-connector.spark.enabled` in application.yaml:

1. **Spark Streaming Mode** (default): Uses Apache Spark Structured Streaming for windowed aggregations with checkpointing and watermarking
2. **Kafka Listener Mode**: Direct Kafka consumption with in-memory buffering (legacy/debugging mode)

### Core Data Flow (Spark Mode)
```
Kafka Topic (inbound-symbol-source)
    ↓
SinkConnectorRunner (on ApplicationReadyEvent)
    ↓
Submits to ExecutorService (2 threads: "ohlc" + optional "debug")
    ↓
OhlcProcessorCommand.command(SparkStreamProfile)
    ↓
OhlcStreamProcessor.read() → Creates Spark streaming Dataset with:
    - Watermark on event_timestamp
    - Window-based grouping (source, symbol, 1-minute window)
    - OHLC aggregations (first, max, min, last, sum, count)
    ↓
OhlcStreamProcessor.startWritten() → foreachBatch processing:
    - DataRowTransformer converts Row → Candle1mEntity
    - ScyllaWriteService.writeCandles1m() with retry logic
    ↓
ScyllaDB (chart_m1 table)
```

### Command Pattern Architecture
The application uses a Command pattern (`Command<I, O>` interface) to encapsulate processing logic:
- `OhlcProcessorCommand`: Orchestrates OHLC candle aggregation pipeline
- `DebugSparkProcessorCommand`: Optional debug logging stream (when `logging.stream.debug=true`)

### Layer Structure
- **presentation/**: `SinkConnectorRunner` (Spring lifecycle hooks)
- **application/command/**: Command implementations (`OhlcProcessorCommand`, `DebugSparkProcessorCommand`)
- **domain/**: Business models (`PriceEventMessage`, `Candle1m`) and processing logic
  - **domain/logic/batchprocessing/**: Spark processing services (`OhlcStreamProcessor`, `ScyllaWriteService`, `DataRowTransformer`)
  - **domain/spark/**: Spark configuration objects (`SparkStreamProfile`, `WriteOptions`)
- **infrastructure/**: Technical concerns
  - **infrastructure/config/**: Spring/Spark/Kafka/Retry configurations
  - **infrastructure/entity/**: ScyllaDB entities (`Candle1mEntity`)
  - **infrastructure/repository/**: Spring Data Cassandra repositories

### Key Design Patterns
1. **Separation of Spark processing logic**: `SchemaProcessor` (base class) defines the JSON schema; `OhlcStreamProcessor` extends it with OHLC-specific aggregation logic
2. **Retry mechanism**: Uses Spring Retry (`RetryTemplate`) with exponential backoff for ScyllaDB writes
3. **Reactive batching**: `ScyllaWriteService` uses RxJava's `Observable.buffer()` to batch candles before writing
4. **Two-phase transformation**: `DataRowTransformer` converts Spark `Row` → `Candle1mEntity` before persistence

## Critical Configuration

### Spark Streaming Settings (application.yaml)
- `sink-connector.spark.streaming.watermark-delay`: Controls late data tolerance (default: 30 seconds)
- `sink-connector.spark.streaming.window-duration`: Aggregation window size (default: 1 minute)
- `sink-connector.spark.streaming.checkpoint-location`: MUST be persistent storage in production (default: /tmp/spark-checkpoint)
- `sink-connector.spark.streaming.batch-interval`: Micro-batch trigger interval (default: 10 seconds)

### Java Module System
The `build.gradle` includes critical JVM arguments to handle Java module restrictions for Spark:
```groovy
jvmArgs = [
    '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    // ... etc
]
```
These are required for Spark's reflection and serialization mechanisms.

### ScyllaDB Schema
The table is `chart_m1` (not `candles_1m` as mentioned in README):
- **Partition key**: (source, symbol)
- **Clustering key**: timestamp (DESC order)
- No `omsServerId` column in actual implementation

## Common Pitfalls

### Checkpoint Directory Issues
If Spark streaming fails to start, verify:
1. Checkpoint location is writable
2. Previous checkpoint data is compatible (delete `/tmp/spark-checkpoint/*` for clean restart)
3. In production, use distributed storage (HDFS, S3) not local /tmp

### Kafka Consumer Group
- Group ID: `sink-connector-group`
- Manual offset commit is enabled (`enable-auto-commit: false`)
- Offset reset strategy: `earliest`

### Spark Session Lifecycle
- `SparkSession` is a Spring Bean (singleton)
- Properly stopped in `SinkConnectorRunner.tearDown()` with `@PreDestroy`
- ExecutorService threads are daemon=false to prevent premature shutdown

### Batch Processing
- RxJava batching in `ScyllaWriteService` uses hardcoded `batchSize=100`
- Separate from Spark micro-batching (controlled by `batch-interval`)
- Retry logic wraps individual batch writes, not entire Spark micro-batches

## Dependencies

Key libraries:
- Spring Boot 3.5.9 (Java 17+)
- Apache Spark 4.0.0 (Scala 2.13)
- Spring Data Cassandra (for ScyllaDB)
- RxJava 3.1.12 (reactive batching)
- Spring Retry + Spring AOP (retry mechanism)
- Lombok (code generation)

## Environment Variables

```bash
# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# ScyllaDB
SCYLLA_CONTACT_POINTS=localhost
SCYLLA_PORT=9042
SCYLLA_DATACENTER=datacenter1

# Spark
SPARK_ENABLED=true
SPARK_MASTER=local[*]
SPARK_CHECKPOINT_DIR=/tmp/spark-checkpoint
```

## Testing Notes

- Current test suite is minimal (`ApplicationTests.java` only)
- No integration tests for Spark streaming or ScyllaDB writes
- When adding tests, consider using embedded Kafka/Cassandra or testcontainers
