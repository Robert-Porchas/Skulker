/** Group exact decimal digits without passing monetary values through Number. */
export function formatDecimal(value: string): string {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) return value;
  const [, sign, whole, fraction] = match;
  return (
    sign +
    whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",") +
    (fraction ? "." + fraction : "")
  );
}
export function formatPercent(value: number | null): string {
  return value === null || !Number.isFinite(value)
    ? "Unavailable"
    : new Intl.NumberFormat("en-US", {
        style: "percent",
        maximumFractionDigits: 2,
      }).format(value);
}
export function isActive(status: string): boolean {
  return ["PENDING", "QUEUED", "RUNNING"].includes(status);
}
export function validMoney(value: string, positive = false): boolean {
  return (
    /^\d{1,24}(\.\d{1,8})?$/.test(value) && (!positive || /[1-9]/.test(value))
  );
}
export function parseWindows(value: string, minimum = 1): number[] {
  const tokens = value.split(",").map((part) => part.trim());
  if (
    !tokens.length ||
    tokens.length > 32 ||
    tokens.some((part) => !/^\d{1,5}$/.test(part))
  )
    throw new Error("Enter 1–32 distinct comma-separated windows.");
  const windows = tokens.map(Number);
  if (
    new Set(windows).size !== windows.length ||
    windows.some((n) => n < minimum || n > 10000)
  )
    throw new Error(
      `Windows must be distinct integers from ${minimum} to 10,000.`,
    );
  return windows;
}
