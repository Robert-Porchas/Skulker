package dev.skulker.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SubmitBatchRequest(
    @NotEmpty @Size(max = 16) List<@NotNull UUID> datasetIds,
    @NotEmpty @Size(max = 32) List<@NotNull @Min(1) @Max(10000) Integer> shortWindows,
    @NotEmpty @Size(max = 32) List<@NotNull @Min(2) @Max(10000) Integer> longWindows,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 24, fraction = 8)
        BigDecimal initialCapital,
    @NotNull @DecimalMin("0") @Digits(integer = 24, fraction = 8) BigDecimal transactionFee) {}
