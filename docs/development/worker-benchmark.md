# Local worker comparison — 2026-11-12

The full 1/2/4/8-worker comparison passed on the owned three-node kind cluster.
All **1,200 timed tasks and 64 warmup tasks succeeded**, with zero failed tasks.
KEDA's original pause annotation was restored and workers subsequently scaled to zero.

## Reproduction and environment

```sh
python3 scripts/monitoring-smoke.py --timeout-seconds 180
python3 scripts/benchmark-workers.py --trials 3 --timeout-seconds 5400
```

Execution: 2026-11-12 02:43:46–02:49:28 UTC. Each worker count received 16 warmup
jobs followed by three 100-job trials using the same deterministic 10,000-bar CSV
and 100 parameter combinations. Each measured trial had the exact requested number
of Ready workers before and after execution, with available Prometheus CPU samples.

Docker Desktop supplied **12 CPUs and approximately 16 GB RAM shared by all three
kind nodes**; the node capacities must not be summed as independent hardware.
Runtime: Kubernetes 1.36.4, kind 0.33.0, Helm 4.3.1, KEDA 2.21.0, Java 21,
PostgreSQL 17.11, RabbitMQ 4.3.7, Prometheus 3.16.0, Grafana 13.3.1.
Workers and API each had a one-CPU/512 MiB limit; PostgreSQL and RabbitMQ also each
had a one-CPU/512 MiB limit. All application images used tag `m5-observability`.

Source HEAD was `bf20618f6ed6cc443184d2d712b8b03d3f1e3da5`, with the benchmark
harness and accompanying documentation still uncommitted (`sourceDirty=true`).
The backend was already committed and both images built from its validated sources.
Harness SHA-256: `1208140596f25faaca66e60943f840ca88294082c7371534db9765a358bcdbbc`.
Raw JSON SHA-256: `d3fa5f402758a845ad8f9492b2c73ff4e7010d9ddc46608b62c588fd8c07d1d3`.
Raw per-trial/sample evidence remains local in ignored `.tooling/worker-benchmark.json`
and its CSV sibling; this report preserves the measured aggregate results.

## Results

| Workers | Mean trial seconds | Median tasks/sec | Tasks/sec range | Mean task latency seconds | Mean sampled process CPU | Peak ready / unacked | Failures |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 15.09 | 6.69 | 6.45–6.74 | 7.49 | 55.00% | 73 / 1 | 0 |
| 2 | 9.28 | 10.69 | 10.32–11.37 | 4.45 | 40.27% | 54 / 2 | 0 |
| 4 | 9.41 | 10.51 | 10.02–11.46 | 4.37 | 32.41% | 60 / 4 | 0 |
| 8 | 10.28 | 9.59 | 9.08–10.63 | 4.59 | 21.42% | 61 / 8 | 0 |

Mean task latency is persisted creation-to-terminal time, including queue wait.
CPU is the mean of sampled JVM process fractions, averaged across workers and
trials; it is neither total CPU consumption nor a count of CPU cores. Queue peaks
are sampled observations and can miss short spikes. Wall duration includes HTTP,
queue probing, publication, execution, result persistence, and polling overhead.
See the [measurement definitions](observability.md) for exact queries and limitations.

Two workers achieved approximately 1.60 times the one-worker median throughput.
Four and eight workers did not improve throughput over two in this experiment.
These results establish successful concurrent execution and a local saturation
point; they do not identify its cause or predict cloud performance. The shared
host, fixed database/broker limits, short trials, retained result payloads, and
sequential trial ordering constrain interpretation. Profiling and varied task
sizes would be the next performance investigation. Synthetic prices make no
statement about strategy profitability.

## Memory regression discovered during load preparation

The previous API list query materialized full stored result histories. With 200
completed 10,000-bar jobs, requesting `/api/jobs?limit=100` disconnected after 3.49
seconds and the 512 MiB API container was OOMKilled (exit 137). Metadata constructor
projections now omit results/configurations and CSV payloads from list queries.

Repeating that same 100-row request succeeded in **49 ms**, with cgroup memory
moving from 320,909,312 to 328,921,088 bytes and no restart. The 10-row request also
passed. These are individual diagnostic observations, not a statistical latency
comparison. Real PostgreSQL integration tests verify response shape, pagination,
and large-payload behavior; all 314 integrated Java tests passed.
