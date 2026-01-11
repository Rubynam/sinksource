package com.example.sinkconnect.service;

import io.confluent.ksql.api.client.Client;
import io.confluent.ksql.api.client.ClientOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Slf4j
@Service
public class KsqlStreamProcessorService {

    @Value("${sink-connector.ksql.server-url}")
    private String ksqlServerUrl;

    @Value("${sink-connector.ksql.timeout-ms}")
    private int timeoutMs;

    @Value("${sink-connector.topics.binance}")
    private String binanceTopic;

    @Value("${sink-connector.topics.huobi}")
    private String huobiTopic;

    private Client ksqlClient;

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing KSQL client with server URL: {}", ksqlServerUrl);

            ClientOptions options = ClientOptions.create()
                    .setHost(extractHost(ksqlServerUrl))
                    .setPort(extractPort(ksqlServerUrl));

            ksqlClient = Client.create(options);

            log.info("KSQL client initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize KSQL client", e);
        }
    }

    public void createStreams() {
        try {
            createBinanceStream();
            createHuobiStream();
            createAggregatedCandleStream();
            log.info("KSQL streams created successfully");
        } catch (Exception e) {
            log.error("Failed to create KSQL streams", e);
        }
    }

    private void createBinanceStream() {
        String createStreamSql = String.format(
                "CREATE STREAM IF NOT EXISTS binance_price_stream (" +
                        "  symbol VARCHAR," +
                        "  source VARCHAR," +
                        "  bidPrice DECIMAL(18, 8)," +
                        "  askPrice DECIMAL(18, 8)," +
                        "  bidQty DECIMAL(18, 8)," +
                        "  askQty DECIMAL(18, 8)," +
                        "  timestamp BIGINT," +
                        "  eventId VARCHAR" +
                        ") WITH (" +
                        "  KAFKA_TOPIC='%s'," +
                        "  VALUE_FORMAT='JSON'," +
                        "  PARTITIONS=2" +
                        ");",
                binanceTopic
        );

        executeKsqlStatement(createStreamSql);
    }

    private void createHuobiStream() {
        String createStreamSql = String.format(
                "CREATE STREAM IF NOT EXISTS huobi_price_stream (" +
                        "  symbol VARCHAR," +
                        "  source VARCHAR," +
                        "  bidPrice DECIMAL(18, 8)," +
                        "  askPrice DECIMAL(18, 8)," +
                        "  bidQty DECIMAL(18, 8)," +
                        "  askQty DECIMAL(18, 8)," +
                        "  timestamp BIGINT," +
                        "  eventId VARCHAR" +
                        ") WITH (" +
                        "  KAFKA_TOPIC='%s'," +
                        "  VALUE_FORMAT='JSON'," +
                        "  PARTITIONS=2" +
                        ");",
                huobiTopic
        );

        executeKsqlStatement(createStreamSql);
    }

    private void createAggregatedCandleStream() {
        String createTableSql =
                "CREATE TABLE IF NOT EXISTS candles_1m_table AS " +
                "SELECT " +
                "  source," +
                "  symbol," +
                "  WINDOWSTART AS window_start," +
                "  EARLIEST_BY_OFFSET(bidPrice) AS open," +
                "  MAX(bidPrice) AS high," +
                "  MIN(askPrice) AS low," +
                "  LATEST_BY_OFFSET(askPrice) AS close," +
                "  SUM(bidQty) AS volume," +
                "  COUNT(*) AS tick_count " +
                "FROM binance_price_stream " +
                "WINDOW TUMBLING (SIZE 1 MINUTE) " +
                "GROUP BY source, symbol " +
                "EMIT CHANGES;";

        executeKsqlStatement(createTableSql);
    }

    private void executeKsqlStatement(String sql) {
        try {
            if (ksqlClient != null) {
                log.debug("Executing KSQL: {}", sql);
                ksqlClient.executeStatement(sql).get();
                log.info("KSQL statement executed successfully");
            } else {
                log.warn("KSQL client is not initialized, skipping statement execution");
            }
        } catch (Exception e) {
            log.error("Failed to execute KSQL statement: {}", sql, e);
        }
    }

    private String extractHost(String url) {
        try {
            return url.replace("http://", "").replace("https://", "").split(":")[0];
        } catch (Exception e) {
            return "localhost";
        }
    }

    private int extractPort(String url) {
        try {
            String portStr = url.replace("http://", "").replace("https://", "").split(":")[1];
            return Integer.parseInt(portStr);
        } catch (Exception e) {
            return 8088;
        }
    }

    @PreDestroy
    public void cleanup() {
        if (ksqlClient != null) {
            log.info("Closing KSQL client");
            ksqlClient.close();
        }
    }
}