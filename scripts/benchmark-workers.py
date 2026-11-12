#!/usr/bin/env python3
"""Measure local 1/2/4/8-worker execution using real PostgreSQL, RabbitMQ and Prometheus."""

import argparse
import contextlib
import csv
import datetime as dt
import importlib.util
import io
import json
import math
from pathlib import Path
import socket
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("skulker_smoke", ROOT / "scripts/kubernetes-smoke.py")
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)
PAUSE = "autoscaling.keda.sh/paused-replicas"
CPU_QUERY = (
    'avg(process_cpu_usage{application="skulker-worker"} '
    'and on(namespace,pod) (up{application="skulker-worker"} == 1))'
)


@contextlib.contextmanager
def forward(cluster, service, remote_port, local_port):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", local_port))
    with tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            cluster.command("port-forward", "svc/" + service, f"{local_port}:{remote_port}", "--address=127.0.0.1"),
            env=cluster.environment, stdout=log, stderr=subprocess.STDOUT, text=True,
        )
        try:
            deadline = time.monotonic() + min(30, cluster.remaining())
            while True:
                if process.poll() is not None:
                    log.seek(0)
                    raise RuntimeError(f"{service} port-forward exited: {log.read()[-1000:]}")
                try:
                    with socket.create_connection(("127.0.0.1", local_port), timeout=1):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError(f"{service} port-forward did not become ready")
                    time.sleep(0.2)
            yield f"http://127.0.0.1:{local_port}"
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def query(cluster, prometheus, expression):
    data = SMOKE.request(cluster, prometheus, "GET", "/api/v1/query?" + urllib.parse.urlencode({"query": expression}))
    if data.get("status") != "success" or data["data"]["resultType"] != "vector":
        raise RuntimeError("Prometheus did not return a successful instant vector")
    values = [float(result["value"][1]) for result in data["data"]["result"]]
    return statistics.fmean(values) if values and all(math.isfinite(value) for value in values) else None


def cpu_usage(cluster, prometheus):
    value = query(cluster, prometheus, CPU_QUERY)
    return value if value is not None and 0 <= value <= 1 else None


def jobs_by_id(cluster, api, wanted=None):
    selected = {}
    offset = 0
    while True:
        page = SMOKE.request(cluster, api, "GET", f"/api/jobs?limit=100&offset={offset}")
        selected.update({job["id"]: job for job in page if wanted is None or job["id"] in wanted})
        if len(page) < 100 or (wanted is not None and set(selected) == set(wanted)):
            break
        offset += 100
    if wanted is not None and set(selected) != set(wanted):
        raise RuntimeError("A benchmark job was missing from persisted job metadata")
    return list(selected.values())


def idle(cluster, api, rabbit_pod):
    active = [job for job in jobs_by_id(cluster, api) if job["status"] in ("PENDING", "QUEUED", "RUNNING")]
    if active:
        raise RuntimeError("Refusing to change worker count while existing jobs are active")
    while sum(SMOKE.queue_depth(cluster, rabbit_pod).values()):
        time.sleep(min(1, cluster.remaining()))


def pin_workers(cluster, replicas, prometheus):
    SMOKE.workers(cluster)  # Check the deployment's Helm ownership before changing its controller.
    cluster.kubectl("annotate", "scaledobject", SMOKE.WORKER, f"{PAUSE}={replicas}", "--overwrite")
    deadline = time.monotonic() + min(600, cluster.remaining())
    while True:
        state = SMOKE.workers(cluster)
        if state["desired"] == replicas and state["ready"] == replicas and state["podCount"] == replicas:
            discovered = query(cluster, prometheus, 'sum(up{application="skulker-worker"} == 1)')
            cpu = cpu_usage(cluster, prometheus)
            if discovered == replicas and cpu is not None:
                return state
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Expected exactly {replicas} Ready and scraped workers with available CPU metrics; last state: {state}")
        time.sleep(min(2, cluster.remaining()))


def batch_request(dataset_id, count):
    if count == 100:
        short, long = list(range(1, 11)), list(range(20, 30))
    else:
        short, long = list(range(1, count // 2 + 1)), [20, 21]
    return {
        "datasetIds": [dataset_id], "shortWindows": short, "longWindows": long,
        "initialCapital": 10000, "transactionFee": 1,
    }


def run_batch(cluster, api, prometheus, rabbit_pod, dataset_id, count, measured):
    started = time.monotonic()
    batch = SMOKE.request(cluster, api, "POST", "/api/batches", batch_request(dataset_id, count))
    if batch["total"] != count:
        raise RuntimeError(f"Expected {count} independent tasks; received {batch['total']}")
    samples = []
    while True:
        if measured:
            depth = SMOKE.queue_depth(cluster, rabbit_pod)
            try:
                cpu = cpu_usage(cluster, prometheus)
            except Exception:
                cpu = None
        batch = SMOKE.request(cluster, api, "GET", f"/api/batches/{batch['id']}")
        observed = time.monotonic()
        if measured:
            samples.append({
                "elapsedSeconds": observed - started,
                "queueReady": depth["ready"], "queueUnacknowledged": depth["unacknowledged"],
                "workerProcessCpuFraction": cpu, "succeeded": batch["succeeded"], "failed": batch["failed"],
            })
        if batch["succeeded"] + batch["failed"] == count:
            elapsed = observed - started
            break
        if batch["status"] == "CANCELLED":
            raise RuntimeError("A benchmark batch was cancelled")
        time.sleep(min(2 if measured else 0.5, cluster.remaining()))
    result = {
        "batchId": batch["id"], "total": count, "succeeded": batch["succeeded"],
        "failed": batch["failed"], "durationSeconds": elapsed, "samples": samples,
    }
    if measured:
        jobs = jobs_by_id(cluster, api, set(batch["jobIds"]))
        latencies = [
            (dt.datetime.fromisoformat(job["updatedAt"].replace("Z", "+00:00"))
             - dt.datetime.fromisoformat(job["createdAt"].replace("Z", "+00:00"))).total_seconds()
            for job in jobs
        ]
        if any(latency < 0 for latency in latencies):
            raise RuntimeError("Persisted job timestamps produced a negative latency")
        cpu_samples = [sample["workerProcessCpuFraction"] for sample in samples if sample["workerProcessCpuFraction"] is not None]
        result.update({
            "tasksPerSecond": count / elapsed,
            "averageTaskLatencySeconds": statistics.fmean(latencies),
            "observedPeakQueueReady": max(sample["queueReady"] for sample in samples),
            "observedPeakQueueUnacknowledged": max(sample["queueUnacknowledged"] for sample in samples),
            "averageWorkerProcessCpuFraction": statistics.fmean(cpu_samples) if cpu_samples else None,
            "cpuSampleCount": len(cpu_samples), "cpuUnavailableSamples": len(samples) - len(cpu_samples), "jobs": jobs,
        })
    return result


def environment(cluster):
    def local(*command):
        result = subprocess.run(command, cwd=ROOT, env=cluster.environment, text=True, capture_output=True, check=False, timeout=15)
        return result.stdout.strip() if result.returncode == 0 else None
    nodes = cluster.resource("nodes")["items"]
    dirty = local("git", "status", "--porcelain")
    return {
        "sourceRevision": local("git", "rev-parse", "HEAD"),
        "sourceDirty": None if dirty is None else bool(dirty),
        "kind": local("kind", "version"), "helm": local("helm", "version", "--short"),
        "kubernetes": json.loads(cluster.kubectl("version", "-o", "json")),
        "nodes": [{
            "name": node["metadata"]["name"],
            "capacity": node["status"]["capacity"], "allocatable": node["status"]["allocatable"],
            "runtime": {key: node["status"]["nodeInfo"].get(key) for key in (
                "kubeletVersion", "containerRuntimeVersion", "operatingSystem", "architecture", "kernelVersion", "osImage",
            )},
        } for node in nodes],
    }


def save_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)
    fields = [
        "workers", "trial", "total", "succeeded", "failed", "durationSeconds", "tasksPerSecond",
        "averageTaskLatencySeconds", "observedPeakQueueReady", "observedPeakQueueUnacknowledged",
        "averageWorkerProcessCpuFraction", "cpuSampleCount", "batchId",
    ]
    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(buffer, fieldnames=fields, extrasaction="ignore")
    writer.writeheader()
    writer.writerows(report["trials"])
    csv_path = path.with_suffix(".csv")
    temporary_csv = csv_path.with_suffix(".csv.tmp")
    temporary_csv.write_text(buffer.getvalue(), encoding="utf-8")
    temporary_csv.replace(csv_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--workers", type=int, nargs="+", choices=[1, 2, 4, 8], default=[1, 2, 4, 8])
    parser.add_argument("--warmup-jobs", type=int, choices=[8, 16], default=16)
    parser.add_argument("--api-port", type=int, default=18080)
    parser.add_argument("--prometheus-port", type=int, default=19090)
    parser.add_argument("--timeout-seconds", type=int, default=3600)
    parser.add_argument("--output", type=Path, default=ROOT / ".tooling/worker-benchmark.json")
    arguments = parser.parse_args()
    if not 1 <= arguments.trials <= 5 or arguments.timeout_seconds <= 0:
        parser.error("trials must be 1..5 and timeout must be positive")
    if len(set(arguments.workers)) != len(arguments.workers):
        parser.error("worker counts must be unique")
    if arguments.api_port == arguments.prometheus_port or any(not 1024 <= port <= 65535 for port in (arguments.api_port, arguments.prometheus_port)):
        parser.error("distinct localhost ports in 1024..65535 are required")
    report = {
        "context": SMOKE.CONTEXT, "namespace": SMOKE.NAMESPACE,
        "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "passed": False,
        "requestedWorkers": arguments.workers, "trialsPerWorker": arguments.trials,
        "warmupJobsPerWorker": arguments.warmup_jobs, "tasksPerTrial": 100, "barsPerDataset": 10000,
        "cpuQuery": CPU_QUERY, "warmups": [], "trials": [],
    }
    cluster = None
    original_pause = None
    pause_owned = False
    try:
        cluster = SMOKE.Cluster(arguments.timeout_seconds)
        report["environment"] = environment(cluster)
        report["workloads"] = SMOKE.validate_resources(cluster)
        scaled = cluster.resource("scaledobject", SMOKE.WORKER)
        if scaled["metadata"].get("annotations", {}).get("meta.helm.sh/release-name") != SMOKE.RELEASE:
            raise RuntimeError("Refusing to change a ScaledObject not owned by the Skulker release")
        original_pause = scaled["metadata"].get("annotations", {}).get(PAUSE)
        rabbit = cluster.resource("pods", "-l", SMOKE.SELECTOR + ",app.kubernetes.io/component=rabbitmq")["items"]
        if len(rabbit) != 1:
            raise RuntimeError("Expected one owned RabbitMQ pod")
        rabbit_pod = rabbit[0]["metadata"]["name"]
        with contextlib.ExitStack() as stack:
            api = stack.enter_context(forward(cluster, "skulker-api", 8080, arguments.api_port))
            prometheus = stack.enter_context(forward(cluster, "skulker-prometheus", 9090, arguments.prometheus_port))
            idle(cluster, api, rabbit_pod)
            dataset = SMOKE.upload(cluster, api, SMOKE.synthetic_csv(), "BENCHMARK")
            report["datasetId"] = dataset["id"]
            for replicas in arguments.workers:
                idle(cluster, api, rabbit_pod)
                pause_owned = True  # Restoration also runs if the annotation succeeds but readiness fails.
                ready_state = pin_workers(cluster, replicas, prometheus)
                warmup = run_batch(cluster, api, prometheus, rabbit_pod, dataset["id"], arguments.warmup_jobs, False)
                report["warmups"].append({"workers": replicas, "readyWorkers": ready_state, **warmup})
                if warmup["failed"]:
                    raise RuntimeError("Warmup failed; benchmark trials were not started")
                for trial in range(1, arguments.trials + 1):
                    idle(cluster, api, rabbit_pod)
                    state = SMOKE.workers(cluster)
                    if state["ready"] != replicas or state["desired"] != replicas or state["podCount"] != replicas:
                        raise RuntimeError("Worker replica count changed before the timed trial")
                    result = run_batch(cluster, api, prometheus, rabbit_pod, dataset["id"], 100, True)
                    after = SMOKE.workers(cluster)
                    result.update({"workerStateBefore": state, "workerStateAfter": after})
                    report["trials"].append({"workers": replicas, "trial": trial, **result})
                    save_report(arguments.output, report)
                    if result["failed"] or result["succeeded"] != 100:
                        raise RuntimeError("Benchmark requires every timed task to succeed")
                    if result["averageWorkerProcessCpuFraction"] is None:
                        raise RuntimeError("No worker CPU measurements were available; CPU remains null")
                    if after["ready"] != replicas or after["desired"] != replicas or after["podCount"] != replicas:
                        raise RuntimeError("Worker replica count changed during the timed trial")
            idle(cluster, api, rabbit_pod)
        report["passed"] = True
        report["fullMatrix"] = set(arguments.workers) == {1, 2, 4, 8}
    except (Exception, KeyboardInterrupt) as error:
        report["error"] = f"{type(error).__name__}: {error}"
    finally:
        if cluster is not None and pause_owned:
            # Cleanup has its own timeout so an expired benchmark deadline cannot prevent restoration.
            annotation = f"{PAUSE}={original_pause}" if original_pause is not None else PAUSE + "-"
            try:
                restored = subprocess.run(
                    cluster.command("annotate", "scaledobject", SMOKE.WORKER, annotation, "--overwrite"),
                    env=cluster.environment, capture_output=True, text=True, timeout=30, check=False,
                )
                if restored.returncode:
                    raise RuntimeError(restored.stderr[-1000:])
                report["pauseAnnotationRestored"] = True
            except Exception as error:
                report["pauseAnnotationRestored"] = False
                report["restorationError"] = f"{type(error).__name__}: {error}"
                report["passed"] = False
        report["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        save_report(arguments.output, report)
    print(json.dumps({"passed": report["passed"], "evidence": str(arguments.output), "error": report.get("error")}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
