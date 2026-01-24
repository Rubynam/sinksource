package com.example.sinkconnect.utils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

public final class TimeConverter {

    private TimeConverter(){
        //do nothing
    }

    public static Instant convertToInstant(Object value) {
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

    public static BigDecimal convertToBigDecimal(Object value) {
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
