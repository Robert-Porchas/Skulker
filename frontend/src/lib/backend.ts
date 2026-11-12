/** Server boundary: preserve financial number tokens before JS binary floating-point conversion. */
const EXACT_FIELDS = new Set([
  "initialCapital",
  "transactionFee",
  "finalPortfolioValue",
  "cash",
  "close",
  "portfolioValue",
  "price",
  "fee",
  "cashAfter",
  "quantity",
  "shares",
]);
type SourceContext = { source?: string };
type SourceParse = (
  text: string,
  reviver: (key: string, value: unknown, context: SourceContext) => unknown,
) => unknown;

export function parseBackendJson(text: string): unknown {
  return (JSON.parse as SourceParse)(text, (key, value, context) => {
    if (EXACT_FIELDS.has(key) && typeof value === "number") {
      if (!context.source)
        throw new Error(
          "Lossless JSON source context is unavailable; Node 24 is required.",
        );
      return context.source;
    }
    return value;
  });
}

export class ProxyError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ProxyError";
    this.status = status;
  }
}
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
export function resolveBackendTarget(
  base: string,
  segments: string[],
  query: URLSearchParams,
  method: string,
): URL {
  if (method !== "GET" && method !== "POST")
    throw new ProxyError(405, "Method is not supported.");
  const [collection, id, suffix] = segments;
  const list =
    segments.length === 1 &&
    (collection === "datasets" || collection === "jobs");
  const detail =
    segments.length === 2 &&
    ["datasets", "jobs", "batches"].includes(collection) &&
    UUID.test(id);
  const child =
    segments.length === 3 &&
    UUID.test(id) &&
    ((collection === "datasets" && suffix === "csv") ||
      (collection === "jobs" && suffix === "result"));
  const allowed =
    method === "GET"
      ? list || detail || child
      : segments.length === 1 &&
        ["datasets", "jobs", "batches"].includes(collection);
  if (!allowed) throw new ProxyError(404, "Endpoint is not available.");
  for (const [key, value] of query) {
    if (
      method !== "GET" ||
      !list ||
      !["limit", "offset"].includes(key) ||
      query.getAll(key).length !== 1 ||
      !/^\d+$/.test(value) ||
      !Number.isSafeInteger(Number(value))
    )
      throw new ProxyError(400, "Pagination parameters are invalid.");
    if (
      (key === "limit" && (Number(value) < 1 || Number(value) > 100)) ||
      (key === "offset" && Number(value) > 2147483647)
    )
      throw new ProxyError(400, "Pagination parameters are invalid.");
  }
  let origin: URL;
  try {
    origin = new URL(base);
  } catch {
    throw new ProxyError(
      503,
      "The local API address is not configured correctly.",
    );
  }
  if (
    !["http:", "https:"].includes(origin.protocol) ||
    origin.username ||
    origin.password ||
    origin.pathname !== "/" ||
    origin.search ||
    origin.hash
  )
    throw new ProxyError(
      503,
      "The local API address is not configured correctly.",
    );
  return new URL(
    "/api/" + segments.join("/") + (query.size ? "?" + query.toString() : ""),
    origin,
  );
}

export const MAX_REQUEST_BYTES = 3 * 1024 * 1024;
export async function readBoundedBody(request: Request): Promise<Uint8Array> {
  const declared = request.headers.get("content-length");
  if (
    declared &&
    (!/^\d+$/.test(declared) || Number(declared) > MAX_REQUEST_BYTES)
  )
    throw new ProxyError(413, "Request exceeds the 3 MiB dashboard limit.");
  const reader = request.body?.getReader();
  if (!reader) return new Uint8Array();
  const parts: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_REQUEST_BYTES) {
        await reader.cancel();
        throw new ProxyError(413, "Request exceeds the 3 MiB dashboard limit.");
      }
      parts.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    body.set(part, offset);
    offset += part.byteLength;
  }
  return body;
}

export function requireSameOrigin(request: Request): void {
  const requestUrl = new URL(request.url);
  const host = request.headers.get("host") || requestUrl.host;
  // Next may canonicalize Request.url to localhost while the browser uses 127.0.0.1.
  // Accept only local authorities, so arbitrary Host headers cannot enable DNS rebinding.
  if (!/^(?:localhost|127\.0\.0\.1|\[::1\])(?::\d{1,5})?$/i.test(host)) {
    throw new ProxyError(403, "Access this dashboard through localhost.");
  }
  let expectedOrigin: string;
  try {
    expectedOrigin = new URL(requestUrl.protocol + "//" + host).origin;
  } catch {
    throw new ProxyError(403, "Access this dashboard through localhost.");
  }
  if (request.method !== "POST") return;
  const origin = request.headers.get("origin");
  if (
    (origin !== null && origin !== expectedOrigin) ||
    request.headers.get("sec-fetch-site") === "cross-site"
  )
    throw new ProxyError(403, "Submit requests from this dashboard.");
}
