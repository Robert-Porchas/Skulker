# Local observability and worker benchmarks

The optional Helm monitoring layer runs private Prometheus and Grafana services in
the existing `skulker` namespace. It observes the real API, workers, RabbitMQ, and
durable PostgreSQL job state. No AWS resources, paid data services, or deployment
credentials are needed.

## Enable monitoring

Start from the working [local Kubernetes deployment](kubernetes.md). Rebuild and
load application images after backend instrumentation changes, then enable the
monitoring layer:

```sh
scripts/build-images.sh
scripts/local-cluster.sh deploy
scripts/monitoring-deploy.sh
```

Monitoring defaults to disabled. `monitoring-deploy.sh` first runs the local-cluster
context and certificate-authority guard, then creates the
`skulker-grafana-credentials` Secret only if it is absent. Its `admin-password` value
is generated privately, passed over stdin, and never echoed or placed in Helm
values. Existing credentials remain unchanged.

The Helm upgrade uses `--reset-then-reuse-values`: new chart defaults are applied,
previous release overrides are retained, and monitoring is enabled explicitly.
This handles upgrades from the earlier chart, which had no monitoring subtree.
Prometheus and Grafana image tags and digests are pinned in chart values. The
selected releases are [Prometheus 3.16.0](https://github.com/prometheus/prometheus/releases/tag/v3.16.0)
and [Grafana 13.3.1](https://github.com/grafana/grafana/releases/tag/v13.3.1).

Both services are ClusterIP-only. Open separate terminals for local access:

```sh
export KUBECONFIG="$PWD/.tooling/kubeconfig"
kubectl --context kind-skulker -n skulker port-forward svc/skulker-prometheus 19090:9090 --address=127.0.0.1
```

```sh
export KUBECONFIG="$PWD/.tooling/kubeconfig"
kubectl --context kind-skulker -n skulker port-forward svc/skulker-grafana 13000:3000 --address=127.0.0.1
```

Prometheus is at `http://127.0.0.1:19090`. Grafana is at
`http://127.0.0.1:13000`; sign in as `admin` using the password in
`skulker-grafana-credentials/admin-password`. The provisioned **Skulker operations**
dashboard uses datasource UID `skulker-prometheus` and requires no manual imports.

Prometheus retains six hours of local samples in a bounded `emptyDir`; Grafana's
database is also temporary. Pod replacement discards this monitoring storage.
The dashboard and datasource are provisioned from ConfigMaps on every startup.
Application datasets and backtest results remain in PostgreSQL's separate volume.
Automatic plugin preinstallation and updates are disabled using the documented
[Grafana plugin settings](https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/#preinstall_disabled),
so startup uses the bundled Prometheus datasource in the pinned image.

After deployment completes, verify discovery, durable status gauges, the provisioned
datasource connection, and all dashboard panels:

```sh
python3 scripts/monitoring-smoke.py --timeout-seconds 180
```

This checks authenticated Grafana APIs with credentials held only in memory and writes
sanitized results to `.tooling/monitoring-smoke.json`. Workers may remain scaled to zero.

## What is measured

API and worker application metrics have the common `application` label
`skulker-api` or `skulker-worker`. Custom labels are bounded status/outcome values;
job IDs, dataset IDs, symbols, and uploaded contents are not metric labels.

| Metric | Meaning |
| --- | --- |
| `skulker_jobs{status}` | Periodically refreshed PostgreSQL counts for PENDING, QUEUED, RUNNING, SUCCEEDED, FAILED, and CANCELLED jobs |
| `skulker_store_metrics_up` | Last database-count refresh succeeded (`1`) or failed/has not completed (`0`); failed refreshes retain cached counts |
| `skulker_tasks_succeeded_total` | Successful durable worker completions |
| `skulker_tasks_retried_total`, `skulker_tasks_failed_total` | Durable retries and terminal execution failures |
| `skulker_tasks_duplicates_total`, `skulker_tasks_rejected_total` | Verified busy/terminal duplicates and rejected deliveries |
| `skulker_tasks_active` | Claimed attempts currently executing in that process |
| `skulker_task_duration_seconds_{count,sum,max}` | Worker attempt duration with outcome succeeded/retry/failed/stale; excludes queue wait |
| `skulker_outbox_published_total`, `skulker_outbox_retried_total` | Confirmed publication and committed publication-failure handling events, including retry-budget exhaustion |
| `skulker_outbox_publish_duration_seconds_{count,sum,max}` | Publication timing with outcome confirmed/retry/stale |
| `rabbitmq_queue_messages_ready`, `rabbitmq_queue_messages_unacked` | Broker ready and unacknowledged messages for the two Skulker queues |
| `process_cpu_usage` | JVM-reported process CPU usage fraction, not node CPU or container CPU cores |
| `jvm_memory_used_bytes` | JVM memory usage by area and memory pool |

Prometheus discovers Ready API/worker HTTP endpoints through namespaced
[EndpointSlice discovery](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#kubernetes_sd_config),
scraping `/actuator/prometheus` every five seconds. Its dedicated ServiceAccount
can only get/list/watch pods, services, endpoints, and EndpointSlices in this
namespace. It has no Secret, node, or cluster-wide read permissions.

When monitoring is enabled, a declarative RabbitMQ plugin file enables
`rabbitmq_prometheus` alongside management. The private service exposes port 15692.
Prometheus uses [RabbitMQ's per-object endpoint](https://www.rabbitmq.com/docs/prometheus)
and keeps only `skulker.backtests` and `skulker.backtests.dead` queue series. This
avoids replacing per-queue depth with an unrelated broker-wide aggregate.

Dashboard rates use one-minute windows; mean attempt latency divides timer sum by
timer count. No percentile is invented without histogram buckets. Durable job
counts cover all retained jobs, so they differ from a single benchmark batch.
The store-refresh panel identifies unavailable/stale count snapshots. A successful
scrape target is not a Kubernetes Ready-pod count.

## Run a controlled worker comparison

The benchmark script requires a quiet owned kind deployment with monitoring
enabled. It refuses to change worker count when existing jobs are active, waits
for the queue to drain between trials, and never deletes existing application
data. Close manually opened forwards on ports 18080 and 19090 first, or choose
different ports with the CLI options.

```sh
python3 scripts/benchmark-workers.py --trials 1
```

The default comparison uses exactly **1, 2, 4, and 8 Ready worker pods**, in that
order. It pauses KEDA at each requested replica count using
`autoscaling.keda.sh/paused-replicas`, waits for the matching Ready-pod and successful
scrape counts, and runs 16 warmup jobs before timing the 100-job trials. The
original annotation is restored in `finally`, including failure or interruption;
cleanup has its own timeout. It never substitutes a smaller worker count when the
host cannot schedule eight pods.

Each trial uses the same deterministic 10,000-bar synthetic CSV and the same 100
valid short/long-window combinations. The dataset is uploaded once per benchmark
run. All trading assumptions remain the documented historical engine conventions.
These data are load fixtures, not evidence of strategy profitability.

For additional samples, use:

```sh
python3 scripts/benchmark-workers.py --trials 3 --timeout-seconds 5400
```

`--workers 1 2` is useful for focused diagnostics; its report explicitly marks the
full 1/2/4/8 matrix incomplete. `--warmup-jobs 8` selects a smaller warmup. The tool
uses only Python's standard library and the existing guarded Kubernetes helpers.
Its port-forwards bind only to localhost and are always stopped.

Each result records:

- Monotonic wall time from batch POST through observed terminal completion,
  including submission, queueing, execution, persistence, and polling delay
- Tasks per second and mean persisted `createdAt` to terminal `updatedAt` latency
- Observed ready/unacknowledged queue peaks and failed task count
- Mean sampled worker process CPU fraction, with sample counts and missing values
- Actual worker-pod state before and after the timed trial
- Kubernetes/tool versions, node capacity/runtime, and Git revision/dirty state

CPU uses this actual PromQL expression:

```promql
avg(process_cpu_usage{application="skulker-worker"}
    and on(namespace,pod) (up{application="skulker-worker"} == 1))
```

The reported CPU mean is the arithmetic mean of observed samples, not CPU seconds
or an integral over the entire execution. Missing, nonfinite, or invalid CPU
fractions remain `null`; they are never reported as zero. A timed trial with no CPU
samples fails acceptance. Queue probes and HTTP/Prometheus polling add observer
overhead; sampled peaks may miss brief spikes. Wall-clock throughput includes
polling resolution, while persisted task latency uses the stored terminal times.

Raw JSON and a CSV summary are written atomically to ignored files
`.tooling/worker-benchmark.json` and `.tooling/worker-benchmark.csv`. `--output`
selects another JSON path and writes its sibling CSV. Partial evidence survives a
failed run. No benchmark numbers are claimed until the real comparison executes;
record actual observations in [verification.md](verification.md) and the benchmark
report. Extra workers can compete for the same local CPU, memory, storage, broker,
and database, so scaling improvement must be measured rather than assumed.

The default run retains 400 timed results plus warmups, each with a 10,000-point
equity curve. Repeated trials consume local database disk space. Use the disposable
cluster lifecycle when finished; the benchmark does not erase results automatically.

## Disable the optional layer

```sh
export KUBECONFIG="$PWD/.tooling/kubeconfig"
scripts/local-cluster.sh status
helm upgrade skulker infrastructure/helm/skulker --kube-context kind-skulker -n skulker \
  --reset-then-reuse-values --set monitoring.enabled=false --wait
```

Use the owned kubeconfig and context guard before manual Helm operations. Disabling
monitoring removes its workloads, discovery Role, services, and configuration and
rolls RabbitMQ back to its normal plugin file. The separately created Grafana
credentials Secret remains. The standard local-cluster teardown still removes the
owned kind environment without touching cloud resources.
