package com.example.sinkconnect.application.command;

import com.example.sinkconnect.domain.logic.batchprocessing.DataLoggingStream;
import com.example.sinkconnect.domain.spark.SparkStreamProfile;
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
public class DebugSparkProcessorCommand implements Command<SparkStreamProfile, Void>{

    private final DataLoggingStream dataLoggingStream;

    @Override
    public Void command(SparkStreamProfile input){
        Dataset<Row> source = dataLoggingStream.read(input);
        try {
            dataLoggingStream.startWritten(source,input.getWriteOptions(),input.getChartType());
        } catch (TimeoutException e) {
            log.error("SparkProcessorCommand",e);
        } catch (StreamingQueryException e) {
            throw new RuntimeException(e);
        }
        return null;
    }


}
