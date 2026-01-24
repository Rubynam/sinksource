package com.example.sinkconnect.domain.logic.batchprocessing;

import com.example.sinkconnect.domain.spark.SparkStreamProfile;
import com.example.sinkconnect.domain.spark.WriteOptions;
import com.example.sinkconnect.enumeration.ChartType;
import com.example.sinkconnect.infrastructure.entity.Candle1mEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.streaming.Trigger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.from_json;
import static org.apache.spark.sql.functions.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OhlcStreamProcessor extends SchemaProcessor{

    private final ScyllaWriteService scyllaWriteService;
    private final DataRowTransformer dataRowTransformer;

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
                .foreachBatch((batchDF, batchId) -> {
                    log.info("Processing OHLC batch: {}, rows: {}", batchId, batchDF.count());

                    if (batchDF.isEmpty()) {
                        return;
                    }

                    List<Candle1mEntity> candles = new CopyOnWriteArrayList<>();
                    var iterator = batchDF.toLocalIterator();
                    while (iterator.hasNext()){
                        candles.add(dataRowTransformer.transform(iterator.next()));
                    }

                    log.info("Saving {} candles", candles.size());
                    scyllaWriteService.writeCandles1m(candles);
                })
                .outputMode(writeOptions.getWriteMode())
                .trigger(Trigger.ProcessingTime(writeOptions.getInterval()))
                .option("checkpointLocation", writeOptions.getCheckpointLocation())
                .queryName("OhlcStream")
                .start()
                .awaitTermination();
    }

    Dataset<Row> read(Dataset<Row> kafkaStream, ChartType chartType, String watermarkDelay, String windowDuration) {

        return kafkaStream
                .selectExpr("CAST(value AS STRING) as json")
                .select(from_json(col("json"), getSchema()).as("data"))
                .select("data.*")
                // Convert timestamp
                .withColumn("event_timestamp",
                        to_timestamp(col("timestamp")))
                // Set watermark once
                .withWatermark("event_timestamp", watermarkDelay)
                // Group by window
                .groupBy(
                        window(col("event_timestamp"), windowDuration),
                        col("source"),
                        col("symbol")

                )
                // OHLC aggregation
                .agg(
                        first("bidPrice").as("open"),
                        max("bidPrice").as("high"),
                        min("askPrice").as("low"),
                        last("askPrice").as("close"),
                        sum("bidQty").as("volume"),
                        count("*").as("tick_count")
                )
                // Final columns
                .withColumn("timestamp", col("window.start"))
                .drop("window")
                .select(
                        col("source"),
                        col("symbol"),
                        col("timestamp"),
                        col("open"),
                        col("high"),
                        col("low"),
                        col("close"),
                        col("volume"),
                        col("tick_count")
                )
                .withColumn("created_at", current_timestamp());
    }
}
