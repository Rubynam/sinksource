package com.example.sinkconnect.infrastructure.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("chart_m1")
public class Candle1mEntity {

    @PrimaryKeyColumn(name = "source", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String source;

    @PrimaryKeyColumn(name = "symbol", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private String symbol;

    @PrimaryKeyColumn(name = "timestamp", ordinal = 3, type = PrimaryKeyType.CLUSTERED)
    private Instant timestamp;

    @Column("open")
    private BigDecimal open;

    @Column("high")
    private BigDecimal high;

    @Column("low")
    private BigDecimal low;

    @Column("close")
    private BigDecimal close;

    @Column("volume")
    private BigDecimal volume;

    @Column("tick_count")
    private Long tickCount;

    @Column("created_at")
    private Instant createdAt;
}