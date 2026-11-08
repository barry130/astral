package com.astral.ai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** Database aggregation projection; no stage bodies are loaded for reporting. */
@Data
public class AiUsageAggregate {
    private LocalDate date;
    private long requestCount;
    private long inputTokens;
    private long outputTokens;
    private BigDecimal settledAmount;
    private BigDecimal reservationAmount;
    private String currency;
    private long unknownStages;
}
