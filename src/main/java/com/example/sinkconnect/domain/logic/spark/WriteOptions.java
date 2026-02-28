package com.example.sinkconnect.domain.logic.spark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WriteOptions {
    private String writeMode;
    private String interval;
    private String checkpointLocation;
}
