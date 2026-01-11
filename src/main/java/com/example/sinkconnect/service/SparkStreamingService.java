package com.example.sinkconnect.service;

import com.example.sinkconnect.domain.Candle1m;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.api.java.function.VoidFunction2;
import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.Trigger;
import org.apache.spark.sql.types.DataTypes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.apache.spark.sql.functions.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SparkStreamingService {

    private final SparkSession sparkSession;
    private final ScyllaWriteService scyllaWriteService;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${sink-connector.topics.binance}")
    private String binanceTopic;

    @Value("${sink-connector.topics.huobi}")
    private String huobiTopic;

    @Value("${sink-connector.spark.streaming.checkpoint-location}")
    private String checkpointLocation;

    @Value("${sink-connector.spark.streaming.watermark-delay}")
    private String watermarkDelay;

    @Value("${sink-connector.spark.streaming.window-duration}")
    private String windowDuration;

    @Value("${sink-connector.spark.streaming.batch-interval}")
    private String batchInterval;

    public void startStreaming() {
        try {
            log.info("Starting Spark Structured Streaming for 1-minute candles");

            String topics = binanceTopic + "," + huobiTopic;

            Dataset<Row> kafkaStream = sparkSession
                    .readStream()
                    .format("kafka")
                    .option("kafka.bootstrap.servers", bootstrapServers)
                    .option("subscribe", topics)
                    .option("startingOffsets", "latest")
                    .option("failOnDataLoss", "false")
                    .option("maxOffsetsPerTrigger", "1000")
                    .load();

            Dataset<Row> parsedStream = kafkaStream
                    .selectExpr("CAST(value AS STRING) as json")
                    .select(from_json(col("json"), getSchema()).as("data"))
                    .select("data.*");

            Dataset<Row> timestampedStream = parsedStream
                    .withColumn("event_timestamp",
                            to_timestamp(col("timestamp").divide(1000)))
                    .withWatermark("event_timestamp", watermarkDelay);

            Dataset<Row> candlesAggregated = timestampedStream
                    .groupBy(
                            window(col("event_timestamp"), windowDuration),
                            col("source"),
                            col("symbol")
                    )
                    .agg(
                            first("bidPrice").as("open"),
                            max("bidPrice").as("high"),
                            min("askPrice").as("low"),
                            last("askPrice").as("close"),
                            sum("bidQty").as("volume"),
                            count("*").as("tick_count")
                    )
                    .select(
                            col("source"),
                            col("symbol"),
                            col("window.start").as("timestamp"),
                            col("open"),
                            col("high"),
                            col("low"),
                            col("close"),
                            col("volume"),
                            col("tick_count")
                    );

            Dataset<Row> candlesWithOmsServerId = candlesWithOmsServerId(candlesAggregated);

            StreamingQuery query = candlesWithOmsServerId
                    .writeStream()
                    .foreachBatch((VoidFunction2<Dataset<Row>, Long>) (batchDF, batchId) -> {
                        log.info("Processing batch: {}, rows: {}", batchId, batchDF.count());
                        scyllaWriteService.writeCandles1m(batchDF);
                    })
                    .outputMode("append")
                    .trigger(Trigger.ProcessingTime(batchInterval))
                    .option("checkpointLocation", checkpointLocation)
                    .start();

            log.info("Spark Streaming query started successfully");
            query.awaitTermination();

        } catch (Exception e) {
            log.error("Error in Spark Streaming job", e);
            throw new RuntimeException("Spark Streaming job failed", e);
        }
    }

    private Dataset<Row> candlesWithOmsServerId(Dataset<Row> candles) {
        return candles.withColumn("omsServerId",
                concat(
                        col("source"),
                        lit("_"),
                        expr("date_format(timestamp, 'yyyyMMdd')")
                )
        ).withColumn("created_at", current_timestamp());
    }

    private org.apache.spark.sql.types.StructType getSchema() {
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