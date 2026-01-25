package com.example.sinkconnect.domain.logic.batchprocessing;

import com.example.sinkconnect.domain.logic.Transformer;
import com.example.sinkconnect.infrastructure.entity.Candle1mEntity;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.Row;
import org.springframework.stereotype.Component;

import static com.example.sinkconnect.utils.Formatter.convertToBigDecimal;
import static com.example.sinkconnect.utils.Formatter.convertToInstant;

@Component
@Slf4j
public class DataRowTransformer implements Transformer<Row, Candle1mEntity> {

    @Override
    public Candle1mEntity transform(Row row) {
        return Candle1mEntity.builder()
                .source(row.getAs("source"))
                .symbol(row.getAs("symbol"))
                .timestamp(convertToInstant(row.get(row.fieldIndex("timestamp"))))
                .open(convertToBigDecimal(row.get(row.fieldIndex("open"))))
                .high(convertToBigDecimal(row.get(row.fieldIndex("high"))))
                .low(convertToBigDecimal(row.get(row.fieldIndex("low"))))
                .close(convertToBigDecimal(row.get(row.fieldIndex("close"))))
                .volume(convertToBigDecimal(row.get(row.fieldIndex("volume"))))
                .tickCount(row.getAs("tick_count"))
                .createdAt(convertToInstant(row.get(row.fieldIndex("created_at"))))
                .build();
    }
}
