package com.example.sinkconnect.infrastructure.repository;

import com.example.sinkconnect.infrastructure.entity.Candle1mEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface Candle1mRepository extends CassandraRepository<Candle1mEntity, String> {
}