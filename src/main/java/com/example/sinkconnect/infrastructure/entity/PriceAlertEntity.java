package com.example.sinkconnect.infrastructure.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("price_alerts")
public class PriceAlertEntity {

    @PrimaryKey
    @Column("alert_id")
    private String alertId;

    @Column("symbol")
    private String symbol;

    @Column("source")
    private String source;

    @Column("target_price")
    private BigDecimal targetPrice;

    @Column("condition")
    private String condition;

    @Column("status")
    private String status;

    @Column("hit_count")
    private Integer hitCount;

    @Column("max_hits")
    private Integer maxHits;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}