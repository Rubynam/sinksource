package com.example.sinkconnect.domain.spark;

import com.example.sinkconnect.enumeration.ChartType;
import lombok.*;
import org.apache.spark.sql.streaming.DataStreamReader;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SparkStreamProfile {

    private DataStreamReader dataStreamReader;
    private String streamName;
    private ChartType chartType;
    private String watermarkDelay;
    private String windowDuration;
    private WriteOptions writeOptions;
}

