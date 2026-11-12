import assert from "node:assert/strict";
import test from "node:test";
import {
  formatDecimal,
  formatPercent,
  isActive,
  parseWindows,
  validMoney,
} from "../src/lib/format.ts";

test("money formatting groups exact source digits without rounding or dropping trailing zeros", () => {
  assert.equal(
    formatDecimal("9007199254740993.12345678"),
    "9,007,199,254,740,993.12345678",
  );
  assert.equal(formatDecimal("-1234.00010000"), "-1,234.00010000");
  assert.equal(formatDecimal("0.00000001"), "0.00000001");
  assert.equal(formatDecimal("100.00"), "100.00");
  assert.equal(formatDecimal("1e+30"), "1e+30");
});

test("money validation accepts exact bounded plain decimals and distinguishes positive capital from zero fees", () => {
  for (const value of [
    "0",
    "0.00000000",
    "100",
    "9007199254740993.12345678",
    "999999999999999999999999.99999999",
  ])
    assert.equal(validMoney(value), true, value);
  assert.equal(validMoney("0", true), false);
  assert.equal(validMoney("000.00000000", true), false);
  assert.equal(validMoney("0.00000001", true), true);
  for (const value of [
    "",
    " 1",
    "1 ",
    "-1",
    "+1",
    "1e3",
    "NaN",
    "Infinity",
    "1,000",
    ".5",
    "1.",
    "0.123456789",
    "1000000000000000000000000",
  ])
    assert.equal(validMoney(value), false, value);
});

test("window lists preserve order and enforce valid distinct bounded integer observations", () => {
  assert.deepEqual(parseWindows(" 3, 1, 10000 "), [3, 1, 10000]);
  assert.deepEqual(parseWindows("2, 3", 2), [2, 3]);
  for (const value of [
    "",
    "1,",
    "1,,2",
    "0",
    "-1",
    "1.5",
    "1e2",
    "1,01",
    "10001",
    Array.from({ length: 33 }, (_, n) => n + 1).join(","),
  ])
    assert.throws(() => parseWindows(value), Error, value);
  assert.throws(() => parseWindows("1", 2), /2/);
});

test("unavailable financial ratios remain explicit and terminal statuses stop polling", () => {
  assert.equal(formatPercent(null), "Unavailable");
  assert.equal(formatPercent(Number.NaN), "Unavailable");
  assert.equal(formatPercent(Infinity), "Unavailable");
  assert.equal(formatPercent(-0.73), "-73%");
  for (const state of ["PENDING", "QUEUED", "RUNNING"])
    assert.equal(isActive(state), true);
  for (const state of ["SUCCEEDED", "FAILED", "CANCELLED"])
    assert.equal(isActive(state), false);
});
