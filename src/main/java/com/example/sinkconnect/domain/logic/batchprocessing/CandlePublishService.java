package com.example.sinkconnect.domain.logic.batchprocessing;

import com.example.sinkconnect.domain.common.model.Candle1m;
import com.example.sinkconnect.infrastructure.entity.Candle1mEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandlePublishService {

    private final KafkaTemplate<String, Candle1m> candle1mKafkaTemplate;

    @Value("${sink-connector.topics.chart1m-output:chart1m-data}")
    private String chart1mTopic;

    public void publishCandles(List<Candle1mEntity> candles) {
        candles.forEach(entity -> {
            Candle1m candle = Candle1m.builder()
                    .source(entity.getSource())
                    .symbol(entity.getSymbol())
                    .timestamp(entity.getTimestamp())
                    .open(entity.getOpen())
                    .high(entity.getHigh())
                    .low(entity.getLow())
                    .close(entity.getClose())
                    .createdAt(entity.getCreatedAt())
                    .build();

            String key = entity.getSource() + "_" + entity.getSymbol();

            CompletableFuture<SendResult<String, Candle1m>> future =
                    candle1mKafkaTemplate.send(chart1mTopic, key, candle);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.debug("Published candle for {}-{} to topic {} at partition {}",
                            candle.getSource(), candle.getSymbol(), chart1mTopic,
                            result.getRecordMetadata().partition());
                } else {
                    log.error("Failed to publish candle for {}-{}: {}",
                            candle.getSource(), candle.getSymbol(), ex.getMessage(), ex);
                }
            });
        });
    }
}
