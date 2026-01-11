package com.example.sinkconnect.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 source     text,      -- Provider source: 'BINANCE' or 'HUOBI'
 symbol     text,      -- Trading pair: 'ETHUSDT', 'BTCUSDT'
 date       text,      -- Date bucket in format 'YYYYMMDD' (e.g., '20240115')

 -- Clustering Key
 timestamp  timestamp, -- Candle timestamp (start of 1-minute window)

 -- OHLC Data
 open       decimal,   -- Opening price
 high       decimal,   -- Highest price in the interval
 low        decimal,   -- Lowest price in the interval
 close      decimal,   -- Closing price


 -- Audit Fields
 created_at timestamp, -- Record creation timestamp
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle1m implements Serializable {

    private String source;
    private String symbol;
    private String date;//format partition
    private Instant timestamp;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    @Column("create_at")
    private Instant createdAt;
}