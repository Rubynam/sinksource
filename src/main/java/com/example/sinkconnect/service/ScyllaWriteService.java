package com.example.sinkconnect.service;

import com.example.sinkconnect.domain.Candle1m;
import com.example.sinkconnect.infrastructure.entity.Candle1mEntity;
import com.example.sinkconnect.infrastructure.repository.Candle1mRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScyllaWriteService {

    private final Candle1mRepository candle1mRepository;
    private final RetryTemplate retryTemplate;

    public void writeCandles1m(Dataset<Row> candlesDF) {
        try {
            List<Candle1mEntity> entities = convertToEntities(candlesDF);

            if (entities.isEmpty()) {
                log.warn("No candles to write to ScyllaDB");
                return;
            }

            retryTemplate.execute(context -> {
                log.info("Writing {} candles to ScyllaDB (attempt: {})",
                        entities.size(), context.getRetryCount() + 1);
                candle1mRepository.saveAll(entities);
                log.info("Successfully wrote {} candles to ScyllaDB", entities.size());
                return null;
            });

        } catch (Exception e) {
            log.error("Failed to write candles to ScyllaDB after retries", e);
            throw new RuntimeException("ScyllaDB write failed", e);
        }
    }

    private List<Candle1mEntity> convertToEntities(Dataset<Row> candlesDF) {
        List<Candle1mEntity> entities = new ArrayList<>();

        List<Row> rows = candlesDF.collectAsList();

        for (Row row : rows) {
            try {
                Candle1mEntity entity = Candle1mEntity.builder()
                        .source(row.getAs("source"))
                        .symbol(row.getAs("symbol"))
                        .omsServerId(row.getAs("omsServerId"))
                        .timestamp(convertToInstant(row.get(row.fieldIndex("timestamp"))))
                        .open(convertToBigDecimal(row.get(row.fieldIndex("open"))))
                        .high(convertToBigDecimal(row.get(row.fieldIndex("high"))))
                        .low(convertToBigDecimal(row.get(row.fieldIndex("low"))))
                        .close(convertToBigDecimal(row.get(row.fieldIndex("close"))))
                        .volume(convertToBigDecimal(row.get(row.fieldIndex("volume"))))
                        .tickCount(row.getAs("tick_count"))
                        .createdAt(convertToInstant(row.get(row.fieldIndex("created_at"))))
                        .build();

                entities.add(entity);
            } catch (Exception e) {
                log.error("Error converting row to entity: {}", row, e);
            }
        }

        return entities;
    }

    private Instant convertToInstant(Object value) {
        if (value == null) {
            return Instant.now();
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toInstant();
        }
        if (value instanceof Long) {
            return Instant.ofEpochMilli((Long) value);
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toInstant();
        }
        return Instant.now();
    }

    private BigDecimal convertToBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof scala.math.BigDecimal) {
            return ((scala.math.BigDecimal) value).bigDecimal();
        }
        if (value instanceof Double) {
            return BigDecimal.valueOf((Double) value);
        }
        if (value instanceof Long) {
            return BigDecimal.valueOf((Long) value);
        }
        if (value instanceof Integer) {
            return BigDecimal.valueOf((Integer) value);
        }
        return new BigDecimal(value.toString());
    }
}