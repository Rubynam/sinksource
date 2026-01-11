# Crypto Sink Connector

A Spring Boot application that implements a Kafka sink connector for cryptocurrency price data, processing streams with Apache Spark and storing aggregated candle data in ScyllaDB.

## Architecture Overview

This sink connector consumes price events from Kafka topics published by Binance and Huobi exchange sources, processes them using Apache Spark Structured Streaming or direct Kafka listeners, and stores 1-minute OHLC (Open, High, Low, Close) candle data in ScyllaDB.

### Components

1. **Kafka Consumer**: Consumes price events from `inbound-data-binance` and `inbound-data-huobi` topics
2. **Apache Spark Streaming**: Processes streaming data with windowing aggregations
3. **KSQL Integration**: Optional stream processing using KSQL for advanced queries
4. **ScyllaDB Sink**: Writes aggregated candle data to ScyllaDB with retry mechanism

### Data Flow

```
Kafka Topics (inbound-data-binance, inbound-data-huobi)
         |
         v
  [Kafka Consumer Listener] OR [Spark Structured Streaming]
         |
         v
  [Price Event Aggregation]
   - Group by: source, symbol, 1-minute window
   - Calculate: OHLC, volume, tick count
         |
         v
  [ScyllaDB Write Service]
   - Retry mechanism
   - Batch writes
         |
         v
  ScyllaDB (candles_1m table)
```

## Configuration

### Application Modes

The connector supports two modes:

1. **Kafka Listener Mode** (default): Direct Kafka consumer with buffering
2. **Spark Streaming Mode**: Apache Spark Structured Streaming (set `SPARK_ENABLED=true`)

### Environment Variables

```bash
# Kafka Configuration
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# ScyllaDB Configuration
SCYLLA_CONTACT_POINTS=localhost
SCYLLA_PORT=9042
SCYLLA_DATACENTER=datacenter1

# Spark Configuration (optional)
SPARK_ENABLED=false
SPARK_MASTER=local[*]
SPARK_CHECKPOINT_DIR=/tmp/spark-checkpoint

# KSQL Configuration (optional)
KSQL_SERVER_URL=http://localhost:8088
```

### application.yaml

Key configuration sections:

```yaml
sink-connector:
  topics:
    binance: inbound-data-binance
    huobi: inbound-data-huobi

  spark:
    enabled: false  # Set to true to enable Spark Streaming

  retry:
    max-attempts: 3
    initial-backoff-ms: 1000
    backoff-multiplier: 2.0
```

## Running the Application

### Prerequisites

- Java 17+
- Kafka cluster running
- ScyllaDB cluster running
- (Optional) KSQL server for advanced stream processing
- (Optional) Apache Spark for streaming mode

### Build

```bash
./gradlew build
```

### Run with Kafka Listener Mode

```bash
./gradlew bootRun
```

### Run with Spark Streaming Mode

```bash
SPARK_ENABLED=true ./gradlew bootRun
```

## Data Model

### Input: PriceEventMessage

```json
{
  "symbol": "ETHUSDT",
  "source": "BINANCE",
  "bidPrice": 2045.50,
  "askPrice": 2045.75,
  "bidQty": 10.5,
  "askQty": 8.3,
  "timestamp": 1703001234567,
  "eventId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### Output: Candle1mEntity (ScyllaDB)

| Column | Type | Description |
|--------|------|-------------|
| source | text | Provider source (BINANCE, HUOBI) |
| symbol | text | Trading pair (ETHUSDT, BTCUSDT) |
| omsServerId | text | OMS server ID format: {SOURCE}_{YYYYMMDD} |
| timestamp | timestamp | Candle start time (1-minute window) |
| open | decimal | First bid price in window |
| high | decimal | Highest bid price in window |
| low | decimal | Lowest ask price in window |
| close | decimal | Last ask price in window |
| volume | decimal | Total bid quantity in window |
| tick_count | bigint | Number of price events in window |
| created_at | timestamp | Record creation timestamp |

### Primary Key

```sql
PRIMARY KEY ((source, symbol, omsServerId), timestamp)
```

This partitioning strategy ensures:
- Data from same provider/symbol/day is co-located
- Efficient time-range queries
- Partition size control (max ~1440 candles per day per symbol)

## Features

### 1. Kafka Listener Mode

- Direct consumption from Kafka topics
- In-memory buffering (100 messages per source/symbol)
- Manual offset commit for reliability
- Real-time aggregation and storage

### 2. Spark Streaming Mode

- Spark Structured Streaming with Kafka source
- Tumbling window aggregation (1 minute)
- Watermarking for late data (30 seconds)
- Checkpoint-based fault tolerance
- Micro-batch processing (10 second intervals)

### 3. Retry Mechanism

- Exponential backoff retry policy
- Configurable max attempts (default: 3)
- Backoff multiplier: 2.0
- Max backoff: 30 seconds

### 4. KSQL Integration

- Optional KSQL stream creation
- Advanced stream processing queries
- Real-time analytics capabilities

## Performance Considerations

### Kafka Listener Mode

- **Throughput**: ~500 messages per poll
- **Latency**: Low (immediate processing)
- **Memory**: Bounded by buffer size (100 messages)
- **Use Case**: Low to medium volume, real-time processing

### Spark Streaming Mode

- **Throughput**: Higher (1000 messages per partition per micro-batch)
- **Latency**: Higher (~10 seconds batch interval)
- **Memory**: Configurable (4GB executor, 2GB driver)
- **Use Case**: High volume, complex aggregations

## Monitoring

### Logs

```bash
# Application logs
logging.level.com.example.sinkconnect=DEBUG

# Kafka logs
logging.level.org.apache.kafka=WARN

# Spark logs
logging.level.org.apache.spark=WARN
```

### Metrics

The application logs:
- Kafka consumption metrics (topic, partition, offset)
- ScyllaDB write operations (batch size, success/failure)
- Retry attempts and backoff intervals
- Spark streaming query progress

## Troubleshooting

### Issue: Kafka connection failed

**Solution**: Check `KAFKA_BOOTSTRAP_SERVERS` environment variable and ensure Kafka brokers are accessible.

### Issue: ScyllaDB write failures

**Solution**:
1. Verify ScyllaDB cluster is running
2. Check keyspace `crypto_market_data` exists
3. Verify table `candles_1m` schema matches entity definition
4. Review retry logs for detailed error messages

### Issue: Spark streaming job fails

**Solution**:
1. Ensure checkpoint directory is writable
2. Check Spark master configuration
3. Verify sufficient memory allocation
4. Review Spark UI logs for detailed errors

## ScyllaDB Schema

### Create Keyspace

```cql
CREATE KEYSPACE IF NOT EXISTS crypto_market_data
WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'datacenter1': 3
}
AND durable_writes = true;
```

### Create Table

```cql
CREATE TABLE crypto_market_data.candles_1m (
  source text,
  symbol text,
  omsServerId text,
  timestamp timestamp,
  open decimal,
  high decimal,
  low decimal,
  close decimal,
  volume decimal,
  tick_count bigint,
  created_at timestamp,
  PRIMARY KEY ((source, symbol, omsServerId), timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC)
  AND compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_size': 1,
    'compaction_window_unit': 'DAYS'
  }
  AND default_time_to_live = 7776000;  -- 90 days
```

## Development

### Project Structure

```
sink-connect/
├── src/main/java/com/example/sinkconnect/
│   ├── config/                    # Spring configurations
│   │   ├── KafkaConsumerConfig.java
│   │   ├── SparkConfig.java
│   │   └── RetryConfig.java
│   ├── domain/                    # Domain models
│   │   ├── PriceEventMessage.java
│   │   └── Candle1m.java
│   ├── infrastructure/            # Infrastructure layer
│   │   ├── entity/
│   │   │   └── Candle1mEntity.java
│   │   └── repository/
│   │       └── Candle1mRepository.java
│   ├── service/                   # Business services
│   │   ├── KafkaSinkConnectorService.java
│   │   ├── SparkStreamingService.java
│   │   ├── ScyllaWriteService.java
│   │   └── KsqlStreamProcessorService.java
│   ├── runner/
│   │   └── SinkConnectorRunner.java
│   └── Application.java
├── src/main/resources/
│   └── application.yaml
├── build.gradle
└── README.md
```

## License

This project is part of the cryptocurrency trading demo system.