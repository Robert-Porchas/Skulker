# Historical CSV schema

`ohlcv.csv` is a small synthetic, deterministic fixture, not downloaded market data or evidence of a profitable strategy. Supply the symbol separately when calling `CsvPriceParser.parse(symbol, reader)`. The parser never closes the caller's reader and propagates `IOException`.

Use UTF-8 text when opening a file. The exact header is:

```csv
Date,Open,High,Low,Close,Volume
```

An optional seventh column must use the exact trailing header `Adj Close`. Column names are case-sensitive; missing, extra, duplicate, or reordered columns fail. A byte-order mark at the start of the first nonblank header is accepted.

```csv
Date,Open,High,Low,Close,Volume,Adj Close
2024-01-02,100.00,103.00,99.50,102.00,1200000,101.25
2024-01-03,102.00,104.00,101.00,103.50,1400000,
```

- Dates use exactly `yyyy-MM-dd`, with a real calendar date and a year from `0001` through `9999`. Bars must be strictly chronological. Duplicate or unsorted dates fail rather than being sorted or discarded.
- OHLC and present adjusted-close prices are strictly positive plain decimals. Scientific notation, grouping separators, plus signs, NaN, and infinity are unsupported. Prices retain their input decimal precision. OHLC bounds must be consistent.
- Volume is a nonnegative digits-only integer within the Java `long` range. Fractional, grouped, or scientific values fail.
- No fields may have surrounding whitespace. Blank physical rows, including whitespace-only rows, are skipped. A header-only file is a valid empty named series; a file with no header fails.
- An optional adjusted-close field may be empty; other fields are required. Indicators use raw close and do not silently switch to adjusted data.
- This numeric schema supports unquoted fields only. Quoted fields and embedded delimiters/newlines fail explicitly; this is not a general-purpose RFC 4180 parser.
- Errors include the physical row number. Missing trading dates remain absent; no values are filled or inferred.
