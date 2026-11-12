# Financial calculation conventions

These contracts apply to the initial engine slice. CSV ingestion, strategies, portfolio simulation, cumulative returns, volatility, drawdown, and annualization remain unimplemented.

## Historical data

- A symbol is a nonblank string without surrounding whitespace. Symbol identity is case-sensitive; the engine does not silently normalize it.
- Dates are `LocalDate` trading dates. A series requires strictly increasing dates and rejects duplicates, unsorted input, and mixed symbols.
- An explicitly named series may be empty. Its bars are defensively copied and exposed through an immutable list.
- OHLC prices are strictly positive `BigDecimal` values. Low must not exceed high; open and close must fall within that range. Volume is a nonnegative `long`.
- Adjusted close is optional and, when present, strictly positive. Initial indicators use raw close even when adjusted close exists. They do not account for dividends or splits.
- Missing calendar dates remain absent. The engine neither inserts bars nor forward-fills prices. An observed-bar return may span several calendar days; it is not a calendar-day-normalized return.

## Result alignment and unavailable values

Both indicator methods return an immutable `List<OptionalDouble>` with exactly one entry per input bar, in the same order. Index `i` refers to `series.bars().get(i).date()`. `OptionalDouble.empty()` represents unavailable history, never an artificial zero or NaN. Empty input produces an empty result.

## Daily simple return

For raw closing prices `C`:

```text
r[i] = C[i] / C[i - 1] - 1       for i >= 1
r[0] = unavailable
```

The result is a decimal ratio: `0.05` means 5%. Prices `100, 110, 99` yield `unavailable, 0.10, -0.10`. Constant prices yield zero once a prior observation exists.

## Simple moving average

For a positive window `N`:

```text
SMA[i] = sum(C[i - N + 1] ... C[i]) / N     for i >= N - 1
```

Earlier entries are unavailable. A window larger than the series produces only unavailable entries; `N = 1` returns each closing price. Nonpositive windows are invalid even for an empty series. Prices `100, 110, 99` with `N = 2` yield `unavailable, 105, 104.5`.

## Numerical boundary

Prices remain decimal values. SMA rolling sums use exact decimal addition and subtraction. Daily returns use the equivalent `(current - previous) / previous` form, subtracting exactly before division to preserve small relative changes. Divisions use `MathContext.DECIMAL128`; only the final indicator converts to `double`. This conversion is approximate, may round extremely small magnitudes to zero, and must not be reused as a monetary ledger. A nonfinite conversion fails explicitly rather than returning infinity or NaN. `BigDecimal` inputs cannot represent either NaN or infinity.

Construct decimal prices from strings, for example `new BigDecimal("100.10")`, rather than passing a binary floating-point value to the constructor. Future cash, fees, and portfolio accounting require their own documented currency precision and rounding policy.

## Future backtest contract

Milestone 1 will observe crossover signals after the close of day T and execute at the next available bar's open. A final-day signal cannot execute without a subsequent bar. Before adding simulation, document sizing, fractional shares, fees, insufficient cash, terminal open positions, and annualization. Indicators alone do not execute trades or demonstrate profitability.
