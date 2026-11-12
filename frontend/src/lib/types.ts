/** Money and share counts stay as lossless decimal strings at the browser boundary. */
export type Decimal = string;
export type JobStatus =
  "PENDING" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export interface Dataset {
  id: string;
  symbol: string;
  sha256: string;
  barCount: number;
  startDate: string;
  endDate: string;
  createdAt: string;
}
export interface Job {
  id: string;
  datasetId: string;
  status: JobStatus;
  createdAt: string;
  updatedAt: string;
  failureCode: string | null;
}
export interface StrategyConfiguration {
  shortWindow: number;
  longWindow: number;
  initialCapital: Decimal;
  transactionFee: Decimal;
}
export interface SubmitJob extends StrategyConfiguration {
  datasetId: string;
}
export interface SubmitBatch {
  datasetIds: string[];
  shortWindows: number[];
  longWindows: number[];
  initialCapital: Decimal;
  transactionFee: Decimal;
}
export interface Batch {
  id: string;
  status: JobStatus;
  createdAt: string;
  jobIds: string[];
  total: number;
  succeeded: number;
  failed: number;
}
export interface Trade {
  signalDate: string;
  executionDate: string;
  side: "BUY" | "SELL";
  quantity: Decimal;
  price: Decimal;
  fee: Decimal;
  cashAfter: Decimal;
}
export interface EquityPoint {
  date: string;
  cash: Decimal;
  shares: Decimal;
  close: Decimal;
  portfolioValue: Decimal;
}
export interface BacktestResult {
  configuration: StrategyConfiguration;
  symbol: string;
  startDate: string;
  endDate: string;
  initialCapital: Decimal;
  finalPortfolioValue: Decimal;
  totalReturn: number;
  annualizedReturn: number | null;
  maximumDrawdown: number;
  trades: Trade[];
  equityCurve: EquityPoint[];
  executionMetadata: Record<string, string>;
}
export interface Problem {
  title?: string;
  detail?: string;
  status?: number;
}
