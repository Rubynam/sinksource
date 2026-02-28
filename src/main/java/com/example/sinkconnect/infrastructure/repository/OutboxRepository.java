package com.example.sinkconnect.infrastructure.repository;

import com.example.sinkconnect.infrastructure.entity.OutboxEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends CassandraRepository<OutboxEntity, String> {

    /**
     * Find pending outbox messages for processing
     */
    @Query("SELECT * FROM market.alert_outbox WHERE status = 'PENDING' LIMIT ?0")
    List<OutboxEntity> findPendingMessages(int limit);

    /**
     * Find by alert_id (uses secondary index)
     */
    @Query("SELECT * FROM market.alert_outbox WHERE alert_id = ?0 ALLOW FILTERING")
    List<OutboxEntity> findByAlertId(String alertId);
}