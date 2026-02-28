package com.example.sinkconnect.infrastructure.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("alert_outbox")
public class OutboxEntity {

    @PrimaryKeyColumn(name = "status", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String status;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "outbox_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID outboxId;

    @Column("alert_id")
    private String alertId;

    @Column("symbol")
    private String symbol;

    @Column("source")
    private String source;

    @Column("message_type")
    private String messageType;

    @Column("payload")
    private String payload;

    @Column("processed_at")
    private Instant processedAt;

    @Column("retry_count")
    private Integer retryCount;

    @Column("error_message")
    private String errorMessage;
}