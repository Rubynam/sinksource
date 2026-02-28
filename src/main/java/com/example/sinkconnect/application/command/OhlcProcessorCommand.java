package com.example.sinkconnect.application.command;

import com.example.sinkconnect.domain.logic.batchprocessing.OhlcStreamProcessor;
import com.example.sinkconnect.domain.logic.spark.SparkStreamProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeoutException;

@Component
@Slf4j
@RequiredArgsConstructor
public class OhlcProcessorCommand implements Command<SparkStreamProfile, Void>{

    private final OhlcStreamProcessor ohlcStreamProcessor;

    @Override
    public Void command(SparkStreamProfile input){
        try {
            Dataset<Row> source = ohlcStreamProcessor.read(input);
            ohlcStreamProcessor.startWritten(source,input.getWriteOptions(),input.getChartType());
        } catch (TimeoutException e) {
            log.error("SparkProcessorCommand",e);
        } catch (StreamingQueryException e) {
            throw new RuntimeException(e);
        }
        return null;
    }


}