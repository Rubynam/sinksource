package com.example.sinkconnect.presentation;

import com.example.sinkconnect.application.command.DebugSparkProcessorCommand;
import com.example.sinkconnect.application.command.OhlcProcessorCommand;
import com.example.sinkconnect.domain.spark.SparkStreamProfile;
import com.example.sinkconnect.domain.spark.WriteOptions;
import com.example.sinkconnect.enumeration.ChartType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.DataStreamReader;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Slf4j
@Component
public class SinkConnectorRunner {

    private final DebugSparkProcessorCommand command;
    private final OhlcProcessorCommand ohlcProcessorCommand;
    private final ExecutorService executorService;
    private final SparkSession sparkSession;

    @Value("${sink-connector.spark.enabled:false}")
    private boolean sparkEnabled;

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value(value = "${sink-connector.topic}")
    private String topic;

    @Value(value = "${sink-connector.spark.streaming.watermark-delay}")
    private String watermarkDelay;

    @Value(value = "${sink-connector.spark.streaming.window-duration}")
    private String windowDuration;

    @Value(value = "${sink-connector.spark.streaming.write-mode}")
    private String writeMode;

    @Value(value = "${sink-connector.spark.streaming.batch-interval}")
    private String interval;

    @Value(value = "${sink-connector.spark.streaming.checkpoint-location}")
    private String checkpointLocation;


    public SinkConnectorRunner(DebugSparkProcessorCommand command, OhlcProcessorCommand ohlcProcessorCommand, SparkSession sparkSession) {
        this.command = command;
        this.ohlcProcessorCommand = ohlcProcessorCommand;
        this.executorService = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(@NotNull Runnable r) {
                Thread t = new Thread(r);
                t.setName("Spark-Stream-" + t.getId());
                t.setDaemon(false);
                return t;
            }
        });;
        this.sparkSession = sparkSession;
    }


    @EventListener(ApplicationReadyEvent.class)
    void run() throws Exception {
        log.info("Starting Sink Connector Application");
        if (sparkEnabled) {
            log.info("Spark Streaming is enabled, starting Spark job...");
            var stream = sparkSession
                    .readStream()
                    .format("kafka")
                    .option("kafka.bootstrap.servers", bootstrapServers)
                    .option("subscribe", topic)
                    .option("startingOffsets", "latest")
                    .option("failOnDataLoss", "false")
                    .option("maxOffsetsPerTrigger", "1000");


            executorService.submit(()->{
                try {
                    var ohlcProfile = buildStreamProfile("ohlc",stream);
                    ohlcProcessorCommand.command(ohlcProfile);
                } catch (Exception e) {
                    log.error("Spark Streaming job failed", e);
                }
            });

            executorService.submit(()->{
                try{
                    var debugProfile = buildStreamProfile("debug",stream);
                    command.command(debugProfile);
                }catch (Exception e){
                    log.error("Spark Streaming job failed", e);

                }
            });

        } else {
            log.info("Spark Streaming is disabled. Using Kafka listener mode.");
        }



        log.info("Sink Connector is running. Kafka listeners are active.");
    }

    private SparkStreamProfile buildStreamProfile(String streamName, DataStreamReader dataStreamReader) {
        return SparkStreamProfile.builder()
                .streamName(streamName)
                .dataStreamReader(dataStreamReader)
                .watermarkDelay(watermarkDelay)
                .chartType(ChartType.M1)
                .windowDuration(windowDuration)
                .writeOptions(WriteOptions.builder()
                        .writeMode(writeMode)
                        .interval(interval)
                        .checkpointLocation(checkpointLocation+"/"+streamName)
                        .build())
                .build();
    }


    @PreDestroy
    void tearDown(){
        executorService.shutdown();
    }
}