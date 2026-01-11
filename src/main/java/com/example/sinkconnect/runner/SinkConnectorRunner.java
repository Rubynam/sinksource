package com.example.sinkconnect.runner;

import com.example.sinkconnect.service.SparkStreamingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class SinkConnectorRunner {

    private final SparkStreamingService sparkStreamingService;
    private final ExecutorService executorService;

    @Value("${sink-connector.spark.enabled:false}")
    private boolean sparkEnabled;

    public SinkConnectorRunner(SparkStreamingService sparkStreamingService) {
        this.sparkStreamingService = sparkStreamingService;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    @PostConstruct
    void run() throws Exception {
        log.info("Starting Sink Connector Application");
        if (sparkEnabled) {
            log.info("Spark Streaming is enabled, starting Spark job...");
            executorService.execute(()->{
                try {
                    sparkStreamingService.startStreaming();
                } catch (Exception e) {
                    log.error("Spark Streaming job failed", e);
                }
            });

        } else {
            log.info("Spark Streaming is disabled. Using Kafka listener mode.");
        }

        log.info("Sink Connector is running. Kafka listeners are active.");
    }

    @PreDestroy
    void tearDown(){
        executorService.shutdown();
    }
}