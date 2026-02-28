package com.example.sinkconnect.domain.logic.batchprocessing;

import com.example.sinkconnect.domain.logic.spark.SparkStreamProfile;
import com.example.sinkconnect.domain.logic.spark.WriteOptions;
import com.example.sinkconnect.enumeration.ChartType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeoutException;
import static org.apache.spark.sql.functions.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataLoggingStream extends SchemaProcessor{

    public Dataset<Row> read(SparkStreamProfile input) {
        var kafkaStream = input.getDataStreamReader();
        var chartType = input.getChartType();
        var watermarkDelay = input.getWatermarkDelay();
        var windowDuration = input.getWindowDuration();

        return this.read(kafkaStream.load(), chartType, watermarkDelay, windowDuration);
    }

    public void startWritten(Dataset<Row> datasource, WriteOptions writeOptions, ChartType chartType) throws TimeoutException, StreamingQueryException {
        datasource
                .writeStream()
                .format("console")
                .option("truncate", "true")
                .option("numRows", "10")
                .queryName("debug")
                .start()
                .awaitTermination();
    }

    Dataset<Row> read(Dataset<Row> streaming, ChartType chartType, String watermarkDelay, String windowDuration) {

        return streaming
                // Add partition info
                .withColumn("kafka_partition", col("partition"))
                .withColumn("kafka_offset", col("offset"))
                .withColumn("kafka_timestamp", col("timestamp"))
                // Parse JSON
                .selectExpr("CAST(value AS STRING) as json",
                        "kafka_partition",
                        "kafka_offset",
                        "kafka_timestamp")
                .select(from_json(col("json"), getSchema()).as("data"),
                        col("kafka_partition"),
                        col("kafka_offset"),
                        col("kafka_timestamp"))
                .select("data.*",
                        "kafka_partition",
                        "kafka_offset",
                        "kafka_timestamp")
                // Map partition to source (0 -> Binance, 1 -> Huobi, etc)
                .withColumn("omsServerId",
                        concat(lit("source-"), col("kafka_partition"))
                )
                ;
    }
}