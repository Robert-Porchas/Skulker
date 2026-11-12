import type {
  BacktestResult,
  Batch,
  Dataset,
  Job,
  Problem,
  SubmitBatch,
  SubmitJob,
} from "./types.ts";

export class ApiError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}
export async function requestJson<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { cache: "no-store", ...init });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") throw error;
    throw new ApiError(
      "Cannot reach Skulker. Check the local services and retry.",
      503,
    );
  }
  if (!response.ok) {
    const problem: Problem = await response.json().catch(() => ({}));
    throw new ApiError(
      problem.detail || problem.title || `Request failed (${response.status}).`,
      response.status,
    );
  }
  return response.json() as Promise<T>;
}
function post<T>(path: string, body: unknown): Promise<T> {
  return requestJson(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}
export const api = {
  datasets: (offset = 0, signal?: AbortSignal) =>
    requestJson<Dataset[]>(`/datasets?limit=25&offset=${offset}`, { signal }),
  jobs: (offset = 0, signal?: AbortSignal) =>
    requestJson<Job[]>(`/jobs?limit=25&offset=${offset}`, { signal }),
  job: (id: string, signal?: AbortSignal) =>
    requestJson<Job>(`/jobs/${id}`, { signal }),
  result: (id: string, signal?: AbortSignal) =>
    requestJson<BacktestResult>(`/jobs/${id}/result`, { signal }),
  batch: (id: string, signal?: AbortSignal) =>
    requestJson<Batch>(`/batches/${id}`, { signal }),
  submitJob: (body: SubmitJob) => post<Job>("/jobs", body),
  submitBatch: (body: SubmitBatch) => post<Batch>("/batches", body),
  upload: (symbol: string, file: File) => {
    const body = new FormData();
    body.set("symbol", symbol);
    body.set("file", file);
    return requestJson<Dataset>("/datasets", { method: "POST", body });
  },
  datasetDownload: (id: string) => `/api/datasets/${id}/csv`,
};
