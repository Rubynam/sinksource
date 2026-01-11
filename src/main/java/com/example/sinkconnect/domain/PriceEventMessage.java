package com.example.sinkconnect.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceEventMessage implements Serializable {

    @NotBlank(message = "Symbol is required")
    @JsonProperty("symbol")
    private String symbol;

    @NotBlank(message = "Source is required")
    @JsonProperty("source")
    private String source;

    @NotNull(message = "Bid price is required")
    @Positive(message = "Bid price must be positive")
    @JsonProperty("bidPrice")
    private BigDecimal bidPrice;

    @NotNull(message = "Ask price is required")
    @Positive(message = "Ask price must be positive")
    @JsonProperty("askPrice")
    private BigDecimal askPrice;

    @NotNull(message = "Bid quantity is required")
    @PositiveOrZero(message = "Bid quantity must be non-negative")
    @JsonProperty("bidQty")
    private BigDecimal bidQty;

    @NotNull(message = "Ask quantity is required")
    @PositiveOrZero(message = "Ask quantity must be non-negative")
    @JsonProperty("askQty")
    private BigDecimal askQty;

    @NotNull(message = "Timestamp is required")
    @JsonProperty("timestamp")
    private Long timestamp;

    @NotBlank(message = "Event ID is required")
    @JsonProperty("eventId")
    private String eventId;
}