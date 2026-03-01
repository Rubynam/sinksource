-- Alert System Schema for ScyllaDB

-- Keyspace (use existing market keyspace)


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
-- Based on official Akka Persistence Cassandra schema
-- This table stores all events for event-sourced actors
CREATE TABLE IF NOT EXISTS market.akka_journal (
    persistence_id text,
    partition_nr bigint,
    sequence_nr bigint,
    timestamp timeuuid,
    timebucket text,              -- REQUIRED: Time-based partitioning for tag queries
    writer_uuid text,             -- Actor system UUID that wrote the event
    ser_id int,                   -- Serializer ID for the event
    ser_manifest text,            -- Serializer manifest for the event
    event_manifest text,          -- Event class name for deserialization
    event blob,                   -- Serialized event data
    meta_ser_id int,              -- Serializer ID for metadata
    meta_ser_manifest text,       -- Serializer manifest for metadata
    meta blob,                    -- Serialized metadata
    tags set<text>,               -- Tags for events-by-tag queries
    PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH CLUSTERING ORDER BY (sequence_nr DESC, timestamp DESC)
  AND gc_grace_seconds = 864000
  AND compaction = {
    'class': 'SizeTieredCompactionStrategy',
    'enabled': true,
    'tombstone_compaction_interval': 86400,
    'tombstone_threshold': 0.2,
    'unchecked_tombstone_compaction': false,
    'bucket_high': 1.5,
    'bucket_low': 0.5,
    'max_threshold': 32,
    'min_threshold': 4,
    'min_sstable_size': 50
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

-- Akka Persistence All Persistence IDs table
-- Required for tracking all persistence IDs and cluster sharding remember-entities
CREATE TABLE IF NOT EXISTS market.all_persistence_ids (
    persistence_id text PRIMARY KEY
);

-- Akka Persistence Tag Views table
-- Required for events-by-tag queries (event sourcing projections)
-- This table enables efficient querying of events by their tags
CREATE TABLE IF NOT EXISTS market.tag_views (
    tag_name text,
    persistence_id text,
    sequence_nr bigint,
    timebucket bigint,
    timestamp timeuuid,
    tag_pid_sequence_nr bigint,
    writer_uuid text,
    ser_id int,
    ser_manifest text,
    event_manifest text,
    event blob,
    meta_ser_id int,
    meta_ser_manifest text,
    meta blob,
    PRIMARY KEY ((tag_name, timebucket), timestamp, persistence_id, tag_pid_sequence_nr)
) WITH CLUSTERING ORDER BY (timestamp ASC, persistence_id ASC, tag_pid_sequence_nr ASC)
  AND gc_grace_seconds = 864000
  AND compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_size': 1,
    'compaction_window_unit': 'DAYS'
  };

-- Akka Persistence Tag Write Progress table
-- Tracks the progress of tag writing for each persistence_id
-- Used by the tag writer to know what has been written to tag_views
CREATE TABLE IF NOT EXISTS market.tag_write_progress (
    persistence_id text,
    tag text,
    sequence_nr bigint,
    tag_pid_sequence_nr bigint,
    offset timeuuid,
    PRIMARY KEY (persistence_id, tag)
);

-- Akka Persistence Tag Scanning table
-- Manages tag scanning state for events-by-tag queries
-- Tracks which events have been scanned for tag extraction
CREATE TABLE IF NOT EXISTS market.tag_scanning (
    persistence_id text,
    sequence_nr bigint,
    PRIMARY KEY (persistence_id)
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