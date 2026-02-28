-- Alert System Schema for ScyllaDB

-- Keyspace (use existing market keyspace)
CREATE KEYSPACE IF NOT EXISTS market
WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'datacenter1': 3
}
AND durable_writes = true;

-- Alerts table: Stores price alert configurations
CREATE TABLE IF NOT EXISTS market.price_alerts (
    alert_id text,
    symbol text,
    source text,
    target_price decimal,
    condition text,  -- 'ABOVE', 'BELOW', 'CROSS_ABOVE', 'CROSS_BELOW'
    status text,  -- 'ENABLED', 'DISABLED', 'TRIGGERED', 'EXPIRED'
    hit_count int,
    max_hits int,
    created_at timestamp,
    updated_at timestamp,
    PRIMARY KEY (alert_id)
);

-- Symbol status tracking table
CREATE TABLE IF NOT EXISTS market.symbol_status (
    source text,
    symbol text,
    status text,  -- 'ENABLED', 'DISABLED'
    updated_at timestamp,
    PRIMARY KEY ((source, symbol))
);

-- Akka Persistence Journal table for event sourcing
CREATE TABLE IF NOT EXISTS market.akka_journal (
    persistence_id text,
    partition_nr bigint,
    sequence_nr bigint,
    timestamp timeuuid,
    event blob,
    event_manifest text,
    ser_id int,
    ser_manifest text,
    writer_uuid text,
    PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH CLUSTERING ORDER BY (sequence_nr DESC, timestamp DESC)
  AND gc_grace_seconds = 864000
  AND compaction = {
    'class': 'SizeTieredCompactionStrategy',
    'enabled': true
  };

-- Akka Persistence Snapshot table
CREATE TABLE IF NOT EXISTS market.akka_snapshot (
    persistence_id text,
    sequence_nr bigint,
    timestamp bigint,
    snapshot blob,
    ser_id int,
    ser_manifest text,
    PRIMARY KEY (persistence_id, sequence_nr)
) WITH CLUSTERING ORDER BY (sequence_nr DESC)
  AND gc_grace_seconds = 864000;

-- Akka Persistence Metadata table
CREATE TABLE IF NOT EXISTS market.akka_metadata (
    persistence_id text PRIMARY KEY,
    deleted_to bigint,
    properties map<text, text>
);

-- Outbox Pattern table: Stores notifications to be published
CREATE TABLE IF NOT EXISTS market.alert_outbox (
    outbox_id timeuuid,
    alert_id text,
    symbol text,
    source text,
    message_type text,  -- 'ALERT_TRIGGERED', 'ALERT_LIMIT_REACHED'
    payload text,  -- JSON payload
    status text,  -- 'PENDING', 'PROCESSING', 'SENT', 'FAILED'
    created_at timestamp,
    processed_at timestamp,
    retry_count int,
    error_message text,
    PRIMARY KEY (status, created_at, outbox_id)
) WITH CLUSTERING ORDER BY (created_at ASC, outbox_id ASC)
  AND default_time_to_live = 604800  -- 7 days
  AND compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_size': 1,
    'compaction_window_unit': 'DAYS'
  };

-- Secondary index for querying outbox by alert_id
CREATE INDEX IF NOT EXISTS ON market.alert_outbox (alert_id);

-- Alert trigger history for analytics
CREATE TABLE IF NOT EXISTS market.alert_trigger_history (
    alert_id text,
    triggered_at timestamp,
    symbol text,
    source text,
    trigger_price decimal,
    hit_count int,
    PRIMARY KEY (alert_id, triggered_at)
) WITH CLUSTERING ORDER BY (triggered_at DESC)
  AND default_time_to_live = 7776000  -- 90 days
  AND compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_size': 7,
    'compaction_window_unit': 'DAYS'
  };