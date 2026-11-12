/** Deterministic local HTTP fixtures. This process never calls market or cloud services. */
import {
  createServer,
  type IncomingMessage,
  type ServerResponse,
} from "node:http";
import { createHash } from "node:crypto";
import type {
  Batch,
  Dataset,
  Job,
  StrategyConfiguration,
  SubmitBatch,
  SubmitJob,
} from "../src/lib/types.ts";

const createdAt = "2024-01-10T00:00:00Z";
type StoredDataset = Dataset & { csv: string };
type StoredJob = {
  dto: Job;
  configuration: StrategyConfiguration;
  polls: number;
};
let serial = 0;
let unavailable = false;
let precisionId: string | null = null;
let datasets: StoredDataset[] = [];
let jobs: StoredJob[] = [];
let batches: Batch[] = [];

function id(): string {
  return `00000000-0000-4000-8000-${String(++serial).padStart(12, "0")}`;
}
function send(response: ServerResponse, code: number, value: unknown): void {
  response.writeHead(code, {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify(value));
}
function problem(response: ServerResponse, code: number, detail: string): void {
  send(response, code, {
    title: "Fixture request failed",
    status: code,
    detail,
  });
}
async function body(request: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let length = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    length += buffer.length;
    if (length > 4 * 1024 * 1024) throw new Error("Fixture body too large");
    chunks.push(buffer);
  }
  return Buffer.concat(chunks);
}
function reset(scenario: string): void {
  serial = 0;
  unavailable = scenario === "error";
  precisionId = null;
  datasets = [];
  jobs = [];
  batches = [];
  if (scenario === "precision") {
    const datasetId = id();
    datasets.push({
      id: datasetId,
      symbol: "EXACT",
      csv: "fixture",
      sha256: "a".repeat(64),
      barCount: 4,
      startDate: "2024-01-02",
      endDate: "2024-01-05",
      createdAt,
    });
    const job = makeJob(datasetId, {
      shortWindow: 1,
      longWindow: 2,
      initialCapital: "9007199254740993.12345678",
      transactionFee: "0",
    });
    job.dto.status = "SUCCEEDED";
    precisionId = job.dto.id;
  }
}
function metadata(dataset: StoredDataset): Dataset {
  const { csv: ignored, ...rest } = dataset;
  void ignored;
  return rest;
}
function makeJob(
  datasetId: string,
  configuration: StrategyConfiguration,
): StoredJob {
  const entry: StoredJob = {
    dto: {
      id: id(),
      datasetId,
      status: "PENDING",
      createdAt,
      updatedAt: createdAt,
      failureCode: null,
    },
    configuration: {
      shortWindow: configuration.shortWindow,
      longWindow: configuration.longWindow,
      initialCapital: configuration.initialCapital,
      transactionFee: configuration.transactionFee,
    },
    polls: 0,
  };
  jobs.unshift(entry);
  return entry;
}
function progress(entry: StoredJob): Job {
  if (["PENDING", "QUEUED", "RUNNING"].includes(entry.dto.status)) {
    entry.polls += 1;
    entry.dto.status = entry.polls >= 2 ? "SUCCEEDED" : "RUNNING";
    entry.dto.updatedAt = "2024-01-10T00:00:01Z";
  }
  return entry.dto;
}
function batchStatus(batch: Batch): Batch {
  const members = jobs.filter((job) => batch.jobIds.includes(job.dto.id));
  members.forEach(progress);
  batch.succeeded = members.filter(
    (job) => job.dto.status === "SUCCEEDED",
  ).length;
  batch.failed = members.filter((job) => job.dto.status === "FAILED").length;
  batch.status = batch.succeeded === batch.total ? "SUCCEEDED" : "RUNNING";
  return batch;
}
function result(entry: StoredJob): object {
  const lateEntry = entry.configuration.shortWindow === 2;
  const dates = [
    "2024-01-02",
    "2024-01-03",
    "2024-01-04",
    "2024-01-05",
    "2024-01-08",
    "2024-01-09",
  ];
  const closes = [3, 2, 1, 2, 3, 2];
  const quantity = lateEntry ? 49 : 9;
  const cash = lateEntry ? 1 : 9;
  const finalValue = lateEntry ? 99 : 27;
  return {
    configuration: entry.configuration,
    symbol:
      datasets.find((dataset) => dataset.id === entry.dto.datasetId)?.symbol ??
      "TEST",
    startDate: dates[0],
    endDate: dates[5],
    initialCapital: 100,
    finalPortfolioValue: finalValue,
    totalReturn: lateEntry ? -0.01 : -0.73,
    annualizedReturn: lateEntry
      ? Math.expm1((Math.log(0.99) * 365.25) / 7)
      : -1,
    maximumDrawdown: lateEntry ? 0.01 : 0.73,
    trades: [
      {
        signalDate: lateEntry ? dates[4] : dates[3],
        executionDate: lateEntry ? dates[5] : dates[4],
        side: "BUY",
        quantity,
        price: lateEntry ? 2 : 10,
        fee: 1,
        cashAfter: cash,
      },
    ],
    equityCurve: dates.map((date, index) => {
      const invested = index >= (lateEntry ? 5 : 4);
      return {
        date,
        cash: invested ? cash : 100,
        shares: invested ? quantity : 0,
        close: closes[index],
        portfolioValue: invested ? cash + quantity * closes[index] : 100,
      };
    }),
    executionMetadata: {
      executionPrice: "NEXT_AVAILABLE_OPEN",
      sharePolicy: "WHOLE_SHARES_ALL_AFFORDABLE",
      terminalPositionPolicy: "MARK_TO_LAST_CLOSE",
    },
  };
}
function precisionResult(): string {
  // These are deliberately raw numeric JSON tokens, not Number values or quoted decimals.
  return `{"configuration":{"shortWindow":1,"longWindow":2,"initialCapital":9007199254740993.12345678,"transactionFee":0},
    "symbol":"EXACT","startDate":"2024-01-02","endDate":"2024-01-05","initialCapital":9007199254740993.12345678,"finalPortfolioValue":9007199254740993.12345678,
    "totalReturn":0,"annualizedReturn":0,"maximumDrawdown":0,
    "trades":[{"signalDate":"2024-01-04","executionDate":"2024-01-05","side":"BUY","quantity":9007199254740993,"price":1,"fee":0,"cashAfter":0.12345678}],
    "equityCurve":[{"date":"2024-01-02","cash":9007199254740993.12345678,"shares":0,"close":2,"portfolioValue":9007199254740993.12345678},
    {"date":"2024-01-05","cash":0.12345678,"shares":9007199254740993,"close":1,"portfolioValue":9007199254740993.12345678}],
    "executionMetadata":{"executionPrice":"NEXT_AVAILABLE_OPEN","sharePolicy":"WHOLE_SHARES_ALL_AFFORDABLE"}}`;
}

async function handle(
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  const url = new URL(request.url ?? "/", "http://127.0.0.1:19081");
  if (url.pathname === "/__test/reset" && request.method === "POST") {
    const scenario = JSON.parse((await body(request)).toString() || "{}") as {
      scenario?: string;
    };
    reset(scenario.scenario ?? "empty");
    send(response, 200, { reset: true });
    return;
  }
  if (url.pathname === "/__test/state") {
    send(response, 200, {
      datasets: datasets.length,
      jobs: jobs.length,
      batches: batches.length,
      precisionId,
    });
    return;
  }
  if (url.pathname === "/health") {
    send(response, 200, { status: "UP" });
    return;
  }
  if (unavailable) {
    problem(response, 503, "Historical research service unavailable.");
    return;
  }
  const path = url.pathname.split("/").filter(Boolean);
  if (path[0] !== "api") {
    problem(response, 404, "Unknown fixture route.");
    return;
  }
  if (request.method === "GET" && path.length === 2 && path[1] === "datasets") {
    const offset = Number(url.searchParams.get("offset") ?? 0);
    const limit = Number(url.searchParams.get("limit") ?? 25);
    send(response, 200, datasets.slice(offset, offset + limit).map(metadata));
    return;
  }
  if (request.method === "GET" && path.length === 2 && path[1] === "jobs") {
    jobs.forEach(progress);
    const offset = Number(url.searchParams.get("offset") ?? 0);
    const limit = Number(url.searchParams.get("limit") ?? 25);
    send(
      response,
      200,
      jobs.slice(offset, offset + limit).map((job) => job.dto),
    );
    return;
  }
  if (
    request.method === "POST" &&
    path.length === 2 &&
    path[1] === "datasets"
  ) {
    const bytes = await body(request);
    const boundary = /boundary=(?:"([^"]+)"|([^;]+))/.exec(
      request.headers["content-type"] ?? "",
    );
    if (!boundary) {
      problem(response, 400, "Multipart upload is required.");
      return;
    }
    const parts = bytes
      .toString("utf8")
      .split("--" + (boundary[1] || boundary[2]));
    const symbol =
      parts
        .find((part) => /name="symbol"/.test(part))
        ?.split("\r\n\r\n")[1]
        ?.replace(/\r\n$/, "") ?? "";
    const csv =
      parts
        .find((part) => /name="file"/.test(part))
        ?.split("\r\n\r\n")
        .slice(1)
        .join("\r\n\r\n")
        .replace(/\r\n$/, "") ?? "";
    const lines = csv.trim().split(/\r?\n/);
    if (lines[0] !== "Date,Open,High,Low,Close,Volume" || lines.length < 2) {
      problem(
        response,
        422,
        "CSV row 1: expected the historical OHLCV header.",
      );
      return;
    }
    const dataset: StoredDataset = {
      id: id(),
      symbol,
      csv,
      sha256: createHash("sha256").update(csv).digest("hex"),
      barCount: lines.length - 1,
      startDate: lines[1].split(",")[0],
      endDate: lines.at(-1)!.split(",")[0],
      createdAt,
    };
    datasets.unshift(dataset);
    send(response, 201, metadata(dataset));
    return;
  }
  if (request.method === "POST" && path.length === 2 && path[1] === "jobs") {
    const configuration = JSON.parse(
      (await body(request)).toString(),
    ) as SubmitJob;
    if (!datasets.some((dataset) => dataset.id === configuration.datasetId)) {
      problem(response, 404, "Dataset was not found.");
      return;
    }
    if (
      configuration.shortWindow <= 0 ||
      configuration.shortWindow >= configuration.longWindow
    ) {
      problem(response, 400, "Short window must be smaller than long window.");
      return;
    }
    send(response, 202, makeJob(configuration.datasetId, configuration).dto);
    return;
  }
  if (request.method === "POST" && path.length === 2 && path[1] === "batches") {
    const input = JSON.parse((await body(request)).toString()) as SubmitBatch;
    const ids: string[] = [];
    for (const datasetId of input.datasetIds)
      for (const shortWindow of input.shortWindows)
        for (const longWindow of input.longWindows) {
          if (shortWindow < longWindow)
            ids.push(
              makeJob(datasetId, {
                shortWindow,
                longWindow,
                initialCapital: input.initialCapital,
                transactionFee: input.transactionFee,
              }).dto.id,
            );
        }
    const batch: Batch = {
      id: id(),
      createdAt,
      status: "PENDING",
      jobIds: ids,
      total: ids.length,
      succeeded: 0,
      failed: 0,
    };
    batches.push(batch);
    send(response, 201, batch);
    return;
  }
  if (request.method === "GET" && path[1] === "datasets" && path[2]) {
    const dataset = datasets.find((entry) => entry.id === path[2]);
    if (!dataset) {
      problem(response, 404, "Dataset was not found.");
      return;
    }
    if (path[3] === "csv") {
      response.writeHead(200, { "Content-Type": "text/csv" });
      response.end(dataset.csv);
      return;
    }
    send(response, 200, metadata(dataset));
    return;
  }
  if (request.method === "GET" && path[1] === "jobs" && path[2]) {
    const job = jobs.find((entry) => entry.dto.id === path[2]);
    if (!job) {
      problem(response, 404, "Job was not found.");
      return;
    }
    if (path[3] === "result") {
      if (job.dto.status !== "SUCCEEDED") {
        problem(response, 409, "The result is not ready.");
        return;
      }
      if (job.dto.id === precisionId) {
        response.writeHead(200, { "Content-Type": "application/json" });
        response.end(precisionResult());
        return;
      }
      send(response, 200, result(job));
      return;
    }
    send(response, 200, progress(job));
    return;
  }
  if (request.method === "GET" && path[1] === "batches" && path[2]) {
    const batch = batches.find((entry) => entry.id === path[2]);
    if (!batch) {
      problem(response, 404, "Batch was not found.");
      return;
    }
    send(response, 200, batchStatus(batch));
    return;
  }
  problem(response, 404, "Unknown fixture route.");
}

const server = createServer((request, response) => {
  void handle(request, response).catch((error: unknown) =>
    problem(
      response,
      500,
      error instanceof Error ? error.message : "Fixture failed.",
    ),
  );
});
server.listen(19081, "127.0.0.1", () =>
  console.log("Skulker deterministic mock API on 127.0.0.1:19081"),
);
for (const signal of ["SIGTERM", "SIGINT"] as const)
  process.on(signal, () => server.close(() => process.exit(0)));
