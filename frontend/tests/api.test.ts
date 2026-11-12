import assert from "node:assert/strict";
import test from "node:test";
import { api, ApiError, requestJson } from "../src/lib/api.ts";

test("client requests stay same-origin and successful responses are not cached", async (context) => {
  const stub = context.mock.method(globalThis, "fetch", async () =>
    Response.json([{ id: "fixture" }]),
  );
  assert.deepEqual(await requestJson("/jobs"), [{ id: "fixture" }]);
  assert.equal(stub.mock.calls[0].arguments[0], "/api/jobs");
  assert.equal(stub.mock.calls[0].arguments[1]?.cache, "no-store");
});

test("submission keeps decimal money as strings throughout request serialization", async (context) => {
  const stub = context.mock.method(globalThis, "fetch", async () =>
    Response.json({ id: "job" }),
  );
  await api.submitJob({
    datasetId: "dataset",
    shortWindow: 1,
    longWindow: 2,
    initialCapital: "9007199254740993.12345678",
    transactionFee: "0.00000001",
  });
  const options = stub.mock.calls[0].arguments[1];
  assert.equal(options?.method, "POST");
  assert.deepEqual(JSON.parse(String(options?.body)), {
    datasetId: "dataset",
    shortWindow: 1,
    longWindow: 2,
    initialCapital: "9007199254740993.12345678",
    transactionFee: "0.00000001",
  });
});

test("problem details and HTTP status reach the caller without turning a failure into success", async (context) => {
  context.mock.method(globalThis, "fetch", async () =>
    Response.json({ detail: "CSV row 2 is invalid." }, { status: 422 }),
  );
  await assert.rejects(
    requestJson("/datasets"),
    (error: unknown) =>
      error instanceof ApiError &&
      error.status === 422 &&
      error.message === "CSV row 2 is invalid.",
  );
});

test("non-JSON failures produce a usable status message", async (context) => {
  context.mock.method(
    globalThis,
    "fetch",
    async () => new Response("unavailable", { status: 502 }),
  );
  await assert.rejects(
    requestJson("/jobs"),
    (error: unknown) =>
      error instanceof ApiError &&
      error.status === 502 &&
      error.message === "Request failed (502).",
  );
});

test("transport failures are readable while aborts retain cancellation semantics", async (context) => {
  const stub = context.mock.method(globalThis, "fetch", async () => {
    throw new TypeError("socket unavailable");
  });
  await assert.rejects(
    requestJson("/jobs"),
    (error: unknown) =>
      error instanceof ApiError &&
      error.status === 503 &&
      /Cannot reach Skulker/.test(error.message),
  );
  const abort = new DOMException("Cancelled", "AbortError");
  stub.mock.mockImplementation(async () => {
    throw abort;
  });
  await assert.rejects(
    requestJson("/jobs"),
    (error: unknown) => error === abort,
  );
});
