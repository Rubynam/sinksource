package com.example.sinkconnect.domain.logic.batchprocessing;

import org.apache.spark.sql.types.DataTypes;

public abstract class SchemaProcessor {

    protected org.apache.spark.sql.types.StructType getSchema() {
        return DataTypes.createStructType(new org.apache.spark.sql.types.StructField[]{
                DataTypes.createStructField("symbol", DataTypes.StringType, false),
                DataTypes.createStructField("source", DataTypes.StringType, false),
                DataTypes.createStructField("bidPrice", DataTypes.createDecimalType(18, 8), false),
                DataTypes.createStructField("askPrice", DataTypes.createDecimalType(18, 8), false),
                DataTypes.createStructField("bidQty", DataTypes.createDecimalType(18, 8), false),
                DataTypes.createStructField("askQty", DataTypes.createDecimalType(18, 8), false),
                DataTypes.createStructField("timestamp", DataTypes.LongType, false),
                DataTypes.createStructField("eventId", DataTypes.StringType, false)
        });
    }
}
