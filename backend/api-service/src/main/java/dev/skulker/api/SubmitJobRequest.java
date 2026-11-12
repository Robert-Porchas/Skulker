package dev.skulker.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record SubmitJobRequest(
    @NotNull UUID datasetId,
    @Min(1) @Max(10000) int shortWindow,
    @Min(2) @Max(10000) int longWindow,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 24, fraction = 8)
        BigDecimal initialCapital,
    @NotNull @DecimalMin("0") @Digits(integer = 24, fraction = 8) BigDecimal transactionFee) {}
