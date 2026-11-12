# Financial calculation conventions

These contracts apply to the Milestone 1 historical engine. The calculation core has no infrastructure dependencies; CSV parsing is a separate Reader-based adapter in the same Java library.

## Historical data

- A symbol is a nonblank string without surrounding whitespace. Symbol identity is case-sensitive; the engine does not silently normalize it.
- Dates are `LocalDate` trading dates. A series requires strictly increasing dates and rejects duplicates, unsorted input, and mixed symbols.
- An explicitly named series may be empty. Its bars are defensively copied and exposed through an immutable list.
- OHLC prices are strictly positive `BigDecimal` values. Low must not exceed high; open and close must fall within that range. Volume is a nonnegative `long`.
- Adjusted close is optional and, when present, strictly positive. Initial indicators use raw close even when adjusted close exists. They do not account for dividends or splits.
- Missing calendar dates remain absent. The engine neither inserts bars nor forward-fills prices. An observed-bar return may span several calendar days; it is not a calendar-day-normalized return.

## Result alignment and unavailable values

Time-series indicators return an immutable `List<OptionalDouble>` with exactly one entry per input bar, in the same order. Index `i` refers to `series.bars().get(i).date()`. `OptionalDouble.empty()` represents unavailable history, never an artificial zero or NaN. Empty input produces an empty result. Maximum drawdown returns an unavailable scalar for empty input.

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

Construct decimal prices from strings, for example `new BigDecimal("100.10")`, rather than passing a binary floating-point value to the constructor. Volatility uses DECIMAL128 division and square root. The backtest's exact decimal ledger and fee policy are documented below; currency-specific precision is outside this first version.

## Cumulative return, volatility, and drawdown

Cumulative return is `close[i] / close[0] - 1`; the first value is available and zero.

Rolling volatility is the **unannualized sample standard deviation** of the last N observed-bar daily simple returns, with denominator `N - 1`. N must be at least two. The first available volatility occurs at bar index N because the initial bar has no return. A two-pass decimal mean and squared-deviation calculation avoids unstable squared-sum subtraction; current complexity is O(bars × window).

Point drawdown is `equity / runningPeak - 1`. **Maximum drawdown is reported as the nonnegative loss magnitude**, `max(1 - equity / runningPeak)`, between zero and one. The first equity must be positive; later equity may be zero for a total loss, but never negative. Backtest drawdown includes initial capital as the initial peak, before the first marked observation.

## CSV adapter

`CsvPriceParser.parse(symbol, reader)` validates the documented unquoted schema and never closes the caller's reader. Invalid input fails with physical row numbers; I/O failures propagate. Headers and chronology are strict. See the complete [CSV schema](../../datasets/samples/README.md) and synthetic sample. No external datasets or APIs are required.

## Backtest execution and accounting

- The only strategy is long-only SMA crossover. Configuration requires `0 < shortWindow < longWindow`, positive initial capital, and a nonnegative fixed transaction fee.
- Compare exact decimal SMA sums by cross multiplication. Both current and previous long-window averages must exist. Buy on previous short ≤ long and current short > long; exit on previous short ≥ long and current short < long. Equality alone creates no signal, and the first available averages create no synthetic crossover.
- Observe signals after the close of T. Consume each signal once at the next supplied bar's open, before marking that bar's close. A final-day signal cannot execute. Missing calendar dates do not create artificial execution days.
- Buy whole shares using `floor((cash - fee) / nextOpen)`. Fractional shares and short selling are unsupported. Insufficient cash skips the order without fees; no automatic later retry occurs. Quantities outside the `long` range fail explicitly.
- Sell all held shares. If cash plus proceeds cannot cover the fixed fee, skip the exit without fees; holdings remain open. Cash and holdings never become negative.
- Cash, fees, and fills use exact `BigDecimal` arithmetic without implicit cents rounding. This is a single-currency research ledger; currency conversion, slippage, spread, taxes, and market impact are not modeled.
- Each equity point is `cash + shares × rawClose`. An open terminal position remains marked at the final close; no forced liquidation or hypothetical exit fee is added.
- Empty backtest input is rejected. Insufficient history, constant prices, and monotonic prices without a crossing produce no trades. Every nonempty input produces one equity point per bar.
- Total return is `(finalValue - initialCapital) / initialCapital`. Annualized return is `(finalValue / initialCapital)^(365.25 / elapsedCalendarDays) - 1`, using ACT/365.25 from the first to last observation. It is unavailable for a single trading date. Decimal gross-value handling preserves positive residual capital when simple returns round near a total loss; nonfinite metrics fail explicitly.
- Result metadata contains deterministic engine/policy identifiers, without runtime timestamps. Repeating a run with identical input produces the same output. Synthetic tests establish accounting behavior, not profitability.
