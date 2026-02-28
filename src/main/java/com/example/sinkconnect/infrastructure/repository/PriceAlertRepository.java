package com.example.sinkconnect.infrastructure.repository;

import com.example.sinkconnect.infrastructure.entity.PriceAlertEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface PriceAlertRepository extends CassandraRepository<PriceAlertEntity, String> {


    @Query("SELECT * FROM market.price_alerts WHERE symbol = ?0 AND source = ?1 AND status = 'ENABLED' AND target_price >= ?2 ALLOW FILTERING")
    List<PriceAlertEntity> findActiveAlertsBySymbolAndSource(String symbol, String source, BigDecimal currentPrice);

    /**
     * Find all enabled alerts
     *
     * OPTIMIZED: Uses SAI index on status column
     * This query is efficient because:
     * 1. SAI index on status column (price_alerts_status_sai_idx)
     * 2. No ALLOW FILTERING required with SAI
     * 3. Limit prevents large result sets
     *
     * Query pattern: Single-column WHERE clause using SAI index
     * Required index: price_alerts_status_sai_idx
     */
    @Query("SELECT * FROM market.price_alerts WHERE status = 'ENABLED' LIMIT 1000")
    List<PriceAlertEntity> findAllEnabledAlerts();
}