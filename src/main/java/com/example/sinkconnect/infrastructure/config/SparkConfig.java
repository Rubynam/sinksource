package com.example.sinkconnect.infrastructure.config;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.SparkSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SparkConfig {

    @Value("${sink-connector.spark.app-name}")
    private String appName;

    @Value("${sink-connector.spark.master}")
    private String master;

    @Value("${sink-connector.spark.executor.memory}")
    private String executorMemory;

    @Value("${sink-connector.spark.executor.cores}")
    private int executorCores;

    @Value("${sink-connector.spark.driver.memory}")
    private String driverMemory;

    @Value("${sink-connector.spark.sql.shuffle-partitions}")
    private int shufflePartitions;

    @Bean
    public SparkConf sparkConf() {

        return new SparkConf()
                .setAppName(appName)
                .setMaster(master)
                .set("spark.executor.memory", executorMemory)
                .set("spark.executor.cores", String.valueOf(executorCores))
                .set("spark.driver.memory", driverMemory)
                .set("spark.sql.shuffle.partitions", String.valueOf(shufflePartitions))
                .set("spark.serializer", "org.apache.spark.serializer.JavaSerializer")
                .set("spark.kryo.registrationRequired", "false")
                .set("spark.sql.streaming.checkpointLocation", "/tmp/spark-checkpoint")
                .set("spark.streaming.kafka.maxRatePerPartition", "1000")
                .set("spark.sql.adaptive.coalescePartitions.enabled", "true")
                .set("spark.sql.adaptive.enabled", "true")
                .set("spark.driver.blockManager.port", "0")
                .set("spark.sql.streaming.stateStore.providerClass",
                        "org.apache.spark.sql.execution.streaming.state.RocksDBStateStoreProvider")
                .set("spark.sql.streaming.statefulOperator.checkCorrectness.enabled", "true")
                // State timeout - tuy chỉnh theo window duration
                .set("spark.sql.streaming.stateStore.minDeltasForSnapshot", "10")
                // Watermark delay tolerance
                .set("spark.sql.streaming.schemaInference", "true")
                .set("spark.blockManager.port", "0");
    }

    @Bean
    public SparkSession sparkSession(SparkConf sparkConf) {
        return SparkSession.builder()
                .config(sparkConf)
                .getOrCreate();
    }
}