package com.example.sinkconnect.domain.logic.batchprocessing;

import com.example.sinkconnect.infrastructure.entity.Candle1mEntity;
import com.example.sinkconnect.infrastructure.repository.Candle1mRepository;
import io.reactivex.rxjava3.core.Observable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScyllaWriteService {

    private final Candle1mRepository candle1mRepository;
    private final RetryTemplate retryTemplate;
    private final int batchSize = 100;

    @Transactional
    public void writeCandles1m(List<Candle1mEntity> data) {
        Observable.<Candle1mEntity>fromStream(data.stream())
                .buffer(batchSize)
                .doOnNext(entities->{
                    retryTemplate.execute(context -> {
//                        log.info("Writing {} candles to ScyllaDB (attempt: {})",
//                                entities.size(), context.getRetryCount() + 1);
                        candle1mRepository.saveAll(entities);
//                        log.info("Successfully wrote {} candles to ScyllaDB", entities.size());
                        return null;
                    });
                })
                .subscribe();

    }
}