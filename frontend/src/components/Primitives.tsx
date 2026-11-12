import type { ReactNode } from "react";
import type { JobStatus } from "@/lib/types";

const paths = {
  chart: "M4 18V6m0 12h16M7 14l4-5 4 3 5-8",
  database:
    "M20 6c0 2-3.6 3-8 3S4 8 4 6s3.6-3 8-3 8 1 8 3ZM4 6v12c0 2 3.6 3 8 3s8-1 8-3V6M4 12c0 2 3.6 3 8 3s8-1 8-3",
  activity: "M3 12h4l3-8 4 16 3-8h4",
  upload: "M12 16V3m-5 5 5-5 5 5M4 15v5h16v-5",
  arrow: "M5 12h14m-5-5 5 5-5 5",
  download: "M12 3v13m-5-5 5 5 5-5M4 17v4h16v-4",
  refresh: "M20 11a8 8 0 1 0-2 6M20 4v7h-7",
  layers: "m3 7 9-4 9 4-9 4-9-4Zm0 5 9 4 9-4M3 17l9 4 9-4",
  plus: "M12 5v14M5 12h14",
  check: "m5 12 4 4L19 6",
  close: "m6 6 12 12M6 18 18 6",
  chevron: "m9 5 7 7-7 7",
  alert: "m12 3 10 18H2L12 3Zm0 6v5m0 3h.01",
  grid: "M4 4h6v6H4V4Zm10 0h6v6h-6V4ZM4 14h6v6H4v-6Zm10 0h6v6h-6v-6Z",
};
export function Icon({
  name,
  size = 18,
}: {
  name: keyof typeof paths;
  size?: number;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name]} />
    </svg>
  );
}
export function StatusBadge({ status }: { status: JobStatus }) {
  return (
    <span className="status-badge" data-status={status}>
      <i />
      {status}
    </span>
  );
}
export function ErrorMessage({ children }: { children: ReactNode }) {
  return (
    <p className="error-message" role="alert">
      <Icon name="alert" size={16} />
      <span>{children}</span>
    </p>
  );
}
export function EmptyState({
  icon = "layers",
  title,
  children,
}: {
  icon?: keyof typeof paths;
  title: string;
  children: ReactNode;
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon">
        <Icon name={icon} size={23} />
      </span>
      <strong>{title}</strong>
      <p>{children}</p>
    </div>
  );
}
export function Pagination({
  offset,
  count,
  loading,
  onChange,
  name,
}: {
  offset: number;
  count: number;
  loading: boolean;
  onChange: (offset: number) => void;
  name: string;
}) {
  return (
    <nav className="pagination" aria-label={`${name} pagination`}>
      <span>
        {count ? `${offset + 1}–${offset + count}` : "No records"}
        <span className="muted"> · 25 per page</span>
      </span>
      <div>
        <button
          className="button button-small button-quiet"
          disabled={offset === 0 || loading}
          onClick={() => onChange(Math.max(0, offset - 25))}
        >
          Previous
        </button>
        <button
          className="button button-small button-quiet"
          disabled={count < 25 || loading}
          onClick={() => onChange(offset + 25)}
        >
          Next
          <Icon name="chevron" size={13} />
        </button>
      </div>
    </nav>
  );
}
export function message(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "The request could not be completed. Please retry.";
}
export function shortId(id: string) {
  return id.slice(0, 8);
}
export function timestamp(value: string) {
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "UTC",
  }).format(new Date(value));
}
