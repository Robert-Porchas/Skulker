import {
  parseBackendJson,
  ProxyError,
  readBoundedBody,
  requireSameOrigin,
  resolveBackendTarget,
} from "@/lib/backend";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
type Context = { params: Promise<{ path: string[] }> };
const NO_STORE = { "Cache-Control": "no-store" };

async function proxy(request: Request, context: Context): Promise<Response> {
  try {
    const { path } = await context.params;
    const target = resolveBackendTarget(
      process.env.SKULKER_API_URL || "http://127.0.0.1:8080",
      path,
      new URL(request.url).searchParams,
      request.method,
    );
    requireSameOrigin(request);
    const headers = new Headers({
      Accept:
        path.at(-1) === "csv"
          ? "text/csv, application/problem+json"
          : "application/json",
    });
    let body: Uint8Array<ArrayBuffer> | undefined;
    if (request.method === "POST") {
      const contentType = request.headers.get("content-type") || "";
      const expected =
        path[0] === "datasets"
          ? /^multipart\/form-data;\s*boundary=.+$/i
          : /^application\/json(?:\s*;.*)?$/i;
      if (!expected.test(contentType))
        throw new ProxyError(415, "Request content type is not supported.");
      headers.set("Content-Type", contentType);
      body = new Uint8Array(await readBoundedBody(request));
    }
    const upstream = await fetch(target, {
      method: request.method,
      headers,
      body,
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.any([AbortSignal.timeout(20000), request.signal]),
    });
    if (upstream.ok && path.at(-1) === "csv") {
      return new Response(upstream.body, {
        status: upstream.status,
        headers: {
          ...NO_STORE,
          "Content-Type": "text/csv;charset=UTF-8",
          "Content-Disposition": `attachment; filename="${path[1]}.csv"`,
        },
      });
    }
    const contentType = upstream.headers.get("content-type") || "";
    if (!contentType.includes("json"))
      throw new ProxyError(
        502,
        "The local API returned an unexpected response.",
      );
    const data = parseBackendJson(await upstream.text());
    return Response.json(data, { status: upstream.status, headers: NO_STORE });
  } catch (error) {
    const status = error instanceof ProxyError ? error.status : 503;
    const detail =
      error instanceof ProxyError
        ? error.message
        : "Cannot reach the local API. Check the API, database, and worker services, then retry.";
    return Response.json(
      { title: "Request could not be completed", status, detail },
      { status, headers: NO_STORE },
    );
  }
}
export const GET = proxy;
export const POST = proxy;
