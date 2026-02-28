package com.example.sinkconnect.infrastructure.repository;

import com.example.sinkconnect.infrastructure.entity.PriceAlertEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceAlertRepository extends CassandraRepository<PriceAlertEntity, String> {

    /**
     * Find all alerts for a specific symbol and source
     */
    @Query("SELECT * FROM market.price_alerts WHERE symbol = ?0 AND source = ?1 AND status = 'ENABLED' ALLOW FILTERING")
    List<PriceAlertEntity> findActiveAlertsBySymbolAndSource(String symbol, String source);

    /**
     * Find all enabled alerts
     */
    @Query("SELECT * FROM market.price_alerts WHERE status = 'ENABLED' ALLOW FILTERING")
    List<PriceAlertEntity> findAllEnabledAlerts();
}