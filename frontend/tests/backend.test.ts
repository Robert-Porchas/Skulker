import assert from "node:assert/strict";
import test from "node:test";
import {
  MAX_REQUEST_BYTES,
  parseBackendJson,
  ProxyError,
  readBoundedBody,
  requireSameOrigin,
  resolveBackendTarget,
} from "../src/lib/backend.ts";

const id = "00000000-0000-4000-8000-000000000001";
const origin = "http://127.0.0.1:8080";
const status = (code: number) => (error: unknown) =>
  error instanceof ProxyError && error.status === code;

test("backend decimal and share tokens retain every source digit before float conversion", () => {
  const raw = `{"initialCapital":9007199254740993.12345678,"finalPortfolioValue":9007199254740993.12345678,
    "configuration":{"transactionFee":0.00000001},"trades":[{"quantity":9007199254740993,"price":1.23000000,"fee":0,"cashAfter":0.12345678}],
    "equityCurve":[{"cash":0.12345678,"shares":9007199254740993,"close":1,"portfolioValue":9007199254740993.12345678}],
    "totalReturn":-0.73,"annualizedReturn":null,"maximumDrawdown":0.73,"total":100}`;
  const parsed = parseBackendJson(raw) as {
    initialCapital: string;
    finalPortfolioValue: string;
    configuration: { transactionFee: string };
    trades: {
      quantity: string;
      price: string;
      fee: string;
      cashAfter: string;
    }[];
    equityCurve: {
      cash: string;
      shares: string;
      close: string;
      portfolioValue: string;
    }[];
    totalReturn: number;
    annualizedReturn: null;
    maximumDrawdown: number;
    total: number;
  };
  assert.equal(parsed.initialCapital, "9007199254740993.12345678");
  assert.equal(parsed.finalPortfolioValue, "9007199254740993.12345678");
  assert.equal(parsed.configuration.transactionFee, "0.00000001");
  assert.deepEqual(parsed.trades[0], {
    quantity: "9007199254740993",
    price: "1.23000000",
    fee: "0",
    cashAfter: "0.12345678",
  });
  assert.deepEqual(parsed.equityCurve[0], {
    cash: "0.12345678",
    shares: "9007199254740993",
    close: "1",
    portfolioValue: "9007199254740993.12345678",
  });
  assert.equal(parsed.totalReturn, -0.73);
  assert.equal(parsed.maximumDrawdown, 0.73);
  assert.equal(parsed.annualizedReturn, null);
  assert.equal(parsed.total, 100);
});

test("lossless parsing preserves decimal exponent tokens and existing strings", () => {
  assert.deepEqual(
    parseBackendJson(
      '{"close":1.234567890123456789e+25,"cash":"001.2000","price":null}',
    ),
    {
      close: "1.234567890123456789e+25",
      cash: "001.2000",
      price: null,
    },
  );
  assert.throws(() => parseBackendJson('{"price":NaN}'), SyntaxError);
  assert.throws(() => parseBackendJson("{broken"), SyntaxError);
});

test("proxy resolves only the documented collection, detail and result routes", () => {
  for (const segments of [
    ["datasets"],
    ["jobs"],
    ["datasets", id],
    ["jobs", id],
    ["batches", id],
    ["datasets", id, "csv"],
    ["jobs", id, "result"],
  ]) {
    assert.equal(
      resolveBackendTarget(origin, segments, new URLSearchParams(), "GET").href,
      origin + "/api/" + segments.join("/"),
    );
  }
  for (const collection of ["datasets", "jobs", "batches"]) {
    assert.equal(
      resolveBackendTarget(origin, [collection], new URLSearchParams(), "POST")
        .pathname,
      "/api/" + collection,
    );
  }
  assert.equal(
    resolveBackendTarget(
      origin,
      ["jobs"],
      new URLSearchParams("limit=25&offset=100"),
      "GET",
    ).search,
    "?limit=25&offset=100",
  );
});

test("proxy rejects traversal, arbitrary URLs, unsupported verbs and hidden backend endpoints", () => {
  for (const segments of [
    [],
    ["actuator", "env"],
    ["http:", "attacker.example"],
    ["..", "jobs"],
    ["jobs", "%2e%2e"],
    ["jobs", id, "result", "extra"],
    ["batches"],
    ["jobs", id + "/../../actuator"],
  ]) {
    assert.throws(
      () =>
        resolveBackendTarget(origin, segments, new URLSearchParams(), "GET"),
      status(404),
    );
  }
  assert.throws(
    () =>
      resolveBackendTarget(origin, ["jobs", id], new URLSearchParams(), "POST"),
    status(404),
  );
  assert.throws(
    () =>
      resolveBackendTarget(origin, ["jobs"], new URLSearchParams(), "DELETE"),
    status(405),
  );
});

test("proxy rejects duplicate, misplaced, malformed or unbounded pagination", () => {
  for (const query of [
    "limit=10&limit=20",
    "offset=1&offset=2",
    "url=http://attacker.example",
    "limit=0",
    "limit=101",
    "offset=-1",
    "offset=2147483648",
    "offset=9007199254740993",
    "limit=1e2",
    "limit=",
  ]) {
    assert.throws(
      () =>
        resolveBackendTarget(
          origin,
          ["jobs"],
          new URLSearchParams(query),
          "GET",
        ),
      status(400),
    );
  }
  assert.throws(
    () =>
      resolveBackendTarget(
        origin,
        ["jobs", id],
        new URLSearchParams("offset=1"),
        "GET",
      ),
    status(400),
  );
  assert.throws(
    () =>
      resolveBackendTarget(
        origin,
        ["jobs"],
        new URLSearchParams("limit=1"),
        "POST",
      ),
    status(400),
  );
});

test("backend configuration must be a credential-free HTTP origin", () => {
  for (const base of [
    "not-a-url",
    "file:///etc/passwd",
    "ftp://example.com",
    "http://user:password@example.com",
    "http://example.com/api",
    "http://example.com/?token=secret",
    "http://example.com/#fragment",
  ]) {
    assert.throws(
      () => resolveBackendTarget(base, ["jobs"], new URLSearchParams(), "GET"),
      status(503),
    );
  }
  assert.equal(
    resolveBackendTarget(
      "https://research.example:8443",
      ["jobs"],
      new URLSearchParams(),
      "GET",
    ).origin,
    "https://research.example:8443",
  );
});

test("body limit rejects false declarations and enforces streamed bytes independently", async () => {
  for (const declared of [String(MAX_REQUEST_BYTES + 1), "-1", "many", "1,2"]) {
    await assert.rejects(
      readBoundedBody(
        new Request(origin, {
          method: "POST",
          headers: { "content-length": declared },
          body: "small",
        }),
      ),
      status(413),
    );
  }
  const chunks = [new Uint8Array(MAX_REQUEST_BYTES), new Uint8Array([1])];
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      const chunk = chunks.shift();
      if (chunk) controller.enqueue(chunk);
      else controller.close();
    },
  });
  const request = new Request(origin, {
    method: "POST",
    headers: { "content-length": "1" },
    body: stream,
    duplex: "half",
  } as RequestInit);
  await assert.rejects(readBoundedBody(request), status(413));
});

test("body reader preserves exact bytes at the boundary and supports an absent body", async () => {
  const bytes = new Uint8Array(MAX_REQUEST_BYTES).fill(7);
  assert.deepEqual(
    await readBoundedBody(new Request(origin, { method: "POST", body: bytes })),
    bytes,
  );
  assert.deepEqual(
    await readBoundedBody(new Request(origin)),
    new Uint8Array(),
  );
});

test("same-origin POST policy rejects foreign origins and cross-site fetches", () => {
  requireSameOrigin(
    new Request(origin + "/api/jobs", { method: "POST", headers: { origin } }),
  );
  requireSameOrigin(new Request(origin + "/api/jobs", { method: "POST" }));
  const cases: HeadersInit[] = [
    { origin: "https://attacker.example" },
    { origin: "null" },
    { "sec-fetch-site": "cross-site" },
  ];
  for (const headers of cases) {
    assert.throws(
      () =>
        requireSameOrigin(
          new Request(origin + "/api/jobs", { method: "POST", headers }),
        ),
      status(403),
    );
  }
  requireSameOrigin(
    new Request(origin + "/api/jobs", {
      headers: { origin: "https://attacker.example" },
    }),
  );
});

test("browser origin follows the bound Host when Next canonicalizes the internal URL", () => {
  requireSameOrigin(
    new Request("http://localhost:3000/api/jobs", {
      method: "POST",
      headers: { host: "127.0.0.1:3000", origin: "http://127.0.0.1:3000" },
    }),
  );
  assert.throws(
    () =>
      requireSameOrigin(
        new Request("http://localhost:3000/api/jobs", {
          method: "POST",
          headers: { host: "127.0.0.1:3000", origin: "http://localhost:3000" },
        }),
      ),
    status(403),
  );
});

test("local API reads and writes reject arbitrary or malformed authorities", () => {
  for (const host of [
    "attacker.example:3000",
    "localhost.attacker.example",
    "attacker@localhost",
    "localhost:99999",
    "127.0.0.1/other",
  ]) {
    for (const method of ["GET", "POST"]) {
      assert.throws(
        () =>
          requireSameOrigin(
            new Request("http://localhost:3000/api/jobs", {
              method,
              headers: { host, origin: "http://" + host },
            }),
          ),
        status(403),
      );
    }
  }
});
