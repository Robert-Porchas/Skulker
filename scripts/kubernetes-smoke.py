#!/usr/bin/env python3
"""Check the owned kind deployment; optionally measure real KEDA worker scale-up."""

import argparse
import contextlib
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CONTEXT = "kind-skulker"
NAMESPACE = RELEASE = "skulker"
WORKER = "skulker-worker"
SELECTOR = "app.kubernetes.io/instance=skulker"
TASK_QUEUE = "skulker.backtests"
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
FIXTURE = """Date,Open,High,Low,Close,Volume
2024-01-02,3,3,3,3,100
2024-01-03,2,2,2,2,100
2024-01-04,1,1,1,1,100
2024-01-05,2,2,2,2,100
2024-01-08,10,10,3,3,100
2024-01-09,2,2,2,2,100
"""


class Cluster:
    def __init__(self, timeout):
        self.deadline = time.monotonic() + timeout
        self.environment = os.environ.copy()
        self.environment.setdefault("KUBECONFIG", str(ROOT / ".tooling/kubeconfig"))
        self.environment["PATH"] = str(ROOT / ".tooling/bin") + os.pathsep + self.environment["PATH"]
        self.executable = shutil.which("kubectl", path=self.environment["PATH"])
        if not self.executable:
            raise RuntimeError("kubectl is missing; install it or use .tooling/bin/kubectl")
        context = self.run([self.executable, "config", "get-contexts", CONTEXT, "-o", "name"])
        if context.strip() != CONTEXT:
            raise RuntimeError("Refusing to proceed: owned kind-skulker context was not found")
        # Reuse the read-only local-cluster guard, which also matches this context's CA
        # against kind's actual skulker cluster rather than trusting a context name alone.
        self.run([str(ROOT / "scripts/local-cluster.sh"), "status"])

    def remaining(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Kubernetes verification exceeded its deadline")
        return remaining

    def command(self, *arguments):
        return [self.executable, "--context", CONTEXT, "--namespace", NAMESPACE, *arguments]

    def run(self, command, timeout=45):
        result = subprocess.run(
            command, env=self.environment, text=True, capture_output=True,
            timeout=min(timeout, self.remaining()), check=False,
        )
        if result.returncode:
            raise RuntimeError(f"Command failed: {' '.join(command)}\n{result.stderr[-1500:]}")
        return result.stdout

    def kubectl(self, *arguments):
        return self.run(self.command(*arguments))

    def resource(self, *arguments):
        return json.loads(self.kubectl("get", *arguments, "-o", "json"))


@contextlib.contextmanager
def port_forward(cluster, port):
    # Refuse an occupied port rather than accidentally querying another local application.
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
    with tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            cluster.command("port-forward", "svc/skulker-api", f"{port}:8080", "--address=127.0.0.1"),
            env=cluster.environment, stdout=log, stderr=subprocess.STDOUT, text=True,
        )
        try:
            startup_deadline = time.monotonic() + min(30, cluster.remaining())
            while True:
                if process.poll() is not None:
                    log.seek(0)
                    raise RuntimeError("API port-forward exited: " + log.read()[-1500:])
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=1):
                        break
                except OSError:
                    if time.monotonic() >= startup_deadline:
                        raise TimeoutError("API port-forward did not become ready")
                    time.sleep(0.2)
            yield f"http://127.0.0.1:{port}"
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def request(cluster, base, method, path, body=None, content_type="application/json"):
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode("utf-8")
    message = urllib.request.Request(
        base + path, data=body, method=method, headers={"Content-Type": content_type},
    )
    try:
        with HTTP.open(message, timeout=min(20, cluster.remaining())) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {path}: HTTP {error.code}: {error.read(600).decode('utf-8', 'replace')}") from error


def upload(cluster, base, csv, symbol):
    boundary = "skulker-deterministic-smoke-boundary"
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"symbol\"\r\n\r\n{symbol}\r\n"
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.csv\"\r\n"
        f"Content-Type: text/csv\r\n\r\n{csv}\r\n--{boundary}--\r\n"
    ).encode("utf-8")
    return request(cluster, base, "POST", "/api/datasets", body, f"multipart/form-data; boundary={boundary}")


def validate_resources(cluster):
    workloads = cluster.resource("deployment,statefulset", "-l", SELECTOR)["items"]
    if not workloads:
        raise RuntimeError("No Skulker workloads were found in the owned namespace")
    evidence = []
    for workload in workloads:
        metadata = workload["metadata"]
        if metadata.get("annotations", {}).get("meta.helm.sh/release-name") != RELEASE:
            raise RuntimeError(f"Refusing foreign workload {metadata['name']}")
        for container in workload["spec"]["template"]["spec"]["containers"]:
            if not container.get("readinessProbe") or not container.get("livenessProbe"):
                raise RuntimeError(f"Missing readiness/liveness probes: {metadata['name']}/{container['name']}")
            resources = container.get("resources", {})
            if any(not resources.get(bound, {}).get(unit) for bound in ("requests", "limits") for unit in ("cpu", "memory")):
                raise RuntimeError(f"Missing CPU/memory requests or limits: {metadata['name']}/{container['name']}")
        evidence.append({"kind": workload["kind"], "name": metadata["name"], "probesAndResources": True})
    cluster.run(cluster.command("rollout", "status", "deployment/skulker-api", "--timeout=90s"), timeout=100)
    return evidence


def smoke(cluster, base):
    for path in ("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"):
        if request(cluster, base, "GET", path).get("status") != "UP":
            raise RuntimeError(f"Health endpoint is not UP: {path}")
    dataset = upload(cluster, base, FIXTURE, "SMOKE")
    job = request(cluster, base, "POST", "/api/jobs", {
        "datasetId": dataset["id"], "shortWindow": 1, "longWindow": 2,
        "initialCapital": 100, "transactionFee": 1,
    })
    while job["status"] != "SUCCEEDED":
        if job["status"] in ("FAILED", "CANCELLED"):
            raise RuntimeError(f"Smoke job did not succeed: {job}")
        time.sleep(min(1, cluster.remaining()))
        job = request(cluster, base, "GET", f"/api/jobs/{job['id']}")
    result = request(cluster, base, "GET", f"/api/jobs/{job['id']}/result")
    trades = result["trades"]
    if result["finalPortfolioValue"] != 27 or len(trades) != 1:
        raise RuntimeError("Smoke backtest did not produce final value 27 and exactly one trade")
    if any(trades[0][field] != expected for field, expected in {
        "quantity": 9, "price": 10, "fee": 1, "signalDate": "2024-01-05", "executionDate": "2024-01-08",
    }.items()):
        raise RuntimeError("Smoke trade did not follow the expected next-open/fee conventions")
    return {"datasetId": dataset["id"], "jobId": job["id"], "status": job["status"], "finalPortfolioValue": 27, "tradeCount": 1}


def synthetic_csv():
    lines = ["Date,Open,High,Low,Close,Volume"]
    for index in range(10000):
        date = dt.date(1990, 1, 1) + dt.timedelta(days=index)
        cents = 10000 + (index % 40 - 20) * 25
        price = f"{cents // 100}.{cents % 100:02d}"
        high = f"{(cents + 50) // 100}.{(cents + 50) % 100:02d}"
        low = f"{(cents - 50) // 100}.{(cents - 50) % 100:02d}"
        lines.append(f"{date},{price},{high},{low},{price},1000")
    return "\n".join(lines) + "\n"


def workers(cluster):
    deployment = cluster.resource("deployment", WORKER)
    if deployment["metadata"].get("annotations", {}).get("meta.helm.sh/release-name") != RELEASE:
        raise RuntimeError("Refusing to scale a worker deployment not owned by the Skulker release")
    pods = cluster.resource("pods", "-l", SELECTOR + ",app.kubernetes.io/component=worker")["items"]
    ready = sum(
        not pod["metadata"].get("deletionTimestamp")
        and any(condition["type"] == "Ready" and condition["status"] == "True" for condition in pod.get("status", {}).get("conditions", []))
        for pod in pods
    )
    return {"desired": deployment["spec"].get("replicas", 0), "ready": ready, "podCount": len(pods), "pods": [pod["metadata"]["name"] for pod in pods]}


def queue_depth(cluster, rabbit_pod):
    output = cluster.kubectl(
        "exec", rabbit_pod, "--", "rabbitmqctl", "-q", "list_queues",
        "name", "messages_ready", "messages_unacknowledged", "--formatter=json",
    )
    queues = json.loads(output)
    for queue in queues:
        if queue["name"] == TASK_QUEUE:
            return {"ready": int(queue["messages_ready"]), "unacknowledged": int(queue["messages_unacknowledged"])}
    raise RuntimeError("The durable task queue was not found in RabbitMQ")


def autoscale(cluster, base, evidence):
    scaled = cluster.resource("scaledobject", WORKER)
    spec = scaled["spec"]
    if spec.get("minReplicaCount") != 0 or spec.get("maxReplicaCount") != 8 or spec["scaleTargetRef"]["name"] != WORKER:
        raise RuntimeError("Autoscale verification requires the owned worker ScaledObject with min=0 and max=8")
    triggers = [trigger for trigger in spec.get("triggers", []) if trigger["type"] == "rabbitmq"]
    if not any(trigger.get("metadata", {}).get("queueName") == TASK_QUEUE for trigger in triggers):
        raise RuntimeError("The worker ScaledObject must watch the actual Skulker RabbitMQ task queue")
    evidence["scaledObject"] = {
        "minReplicas": 0, "maxReplicas": 8,
        "pollingInterval": spec.get("pollingInterval"),
        "cooldownPeriod": spec.get("cooldownPeriod"),
        "rabbitTriggerModes": [trigger.get("metadata", {}).get("mode") for trigger in triggers],
    }
    rabbit_pods = cluster.resource("pods", "-l", SELECTOR + ",app.kubernetes.io/component=rabbitmq")["items"]
    if len(rabbit_pods) != 1:
        raise RuntimeError("Expected one owned RabbitMQ pod for queue-depth evidence")
    rabbit_pod = rabbit_pods[0]["metadata"]["name"]
    active_jobs = [job for job in request(cluster, base, "GET", "/api/jobs?limit=100") if job["status"] in ("PENDING", "QUEUED", "RUNNING")]
    depth = queue_depth(cluster, rabbit_pod)
    evidence["baselineQueue"] = depth
    if active_jobs or sum(depth.values()):
        raise RuntimeError("Refusing scale-to-zero while existing jobs or queue deliveries are active")
    # This is the only scaling mutation: all scale-up must come from the real KEDA controller.
    workers(cluster)  # Verify ownership before mutation.
    cluster.kubectl("scale", "deployment/" + WORKER, "--replicas=0")
    while True:
        baseline = workers(cluster)
        if baseline["desired"] == 0 and baseline["podCount"] == 0:
            break
        time.sleep(min(1, cluster.remaining()))
    dataset = upload(cluster, base, synthetic_csv(), "SCALE")
    evidence.update({"baseline": baseline, "datasetId": dataset["id"], "barCount": dataset["barCount"], "batches": [], "samples": [], "maxDesired": 0, "maxReady": 0})
    batches = []
    while True:
        cluster.remaining()
        state = workers(cluster)
        depth = queue_depth(cluster, rabbit_pod)
        evidence["maxDesired"] = max(evidence["maxDesired"], state["desired"])
        evidence["maxReady"] = max(evidence["maxReady"], state["ready"])
        batches = [request(cluster, base, "GET", f"/api/batches/{batch['id']}") for batch in batches]
        evidence["batches"] = batches
        evidence["samples"].append({"at": dt.datetime.now(dt.timezone.utc).isoformat(), "workers": state, "queue": depth})
        if any(batch["failed"] for batch in batches):
            raise RuntimeError("One or more autoscale backtest tasks failed; inspect recorded batch IDs")
        complete = bool(batches) and all(batch["succeeded"] == batch["total"] for batch in batches)
        if complete and evidence["maxReady"] >= 2 and sum(depth.values()) == 0:
            evidence["totalSucceeded"] = sum(batch["succeeded"] for batch in batches)
            return
        if complete and len(batches) == 5 and evidence["maxReady"] < 2:
            raise RuntimeError("500 tasks finished without two Ready worker pods; scale-up acceptance was not met")
        outstanding = sum(batch["total"] - batch["succeeded"] - batch["failed"] for batch in batches)
        if evidence["maxReady"] < 2 and depth["ready"] < 20 and outstanding < 20 and len(batches) < 5:
            batch = request(cluster, base, "POST", "/api/batches", {
                "datasetIds": [dataset["id"]], "shortWindows": list(range(1, 11)),
                "longWindows": list(range(20, 30)), "initialCapital": 10000, "transactionFee": 1,
            })
            if batch["total"] != 100:
                raise RuntimeError("Expected exactly 100 independent jobs per autoscale batch")
            batches.append(batch)
            evidence["batches"] = batches
        time.sleep(min(2, cluster.remaining()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--autoscale", action="store_true", help="also verify real KEDA scaling from zero to at least two Ready workers")
    mode.add_argument("--smoke-only", action="store_true", help="run only the API/backtest smoke check (default)")
    parser.add_argument("--local-port", type=int, default=18080)
    parser.add_argument("--timeout-seconds", type=int, default=900)
    parser.add_argument("--output", type=Path, default=ROOT / ".tooling/kubernetes-smoke.json")
    arguments = parser.parse_args()
    if not 1024 <= arguments.local_port <= 65535 or arguments.timeout_seconds <= 0:
        parser.error("local port must be 1024..65535 and timeout must be positive")
    report = {"context": CONTEXT, "namespace": NAMESPACE, "release": RELEASE, "autoscaleRequested": arguments.autoscale, "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "passed": False}
    exit_code = 1
    try:
        cluster = Cluster(arguments.timeout_seconds)
        report["workloads"] = validate_resources(cluster)
        with port_forward(cluster, arguments.local_port) as base:
            report["smoke"] = smoke(cluster, base)
            if arguments.autoscale:
                report["autoscale"] = {}
                autoscale(cluster, base, report["autoscale"])
        report["passed"] = True
        exit_code = 0
    except (Exception, KeyboardInterrupt) as error:
        report["error"] = f"{type(error).__name__}: {error}"
    finally:
        report["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = arguments.output.with_suffix(arguments.output.suffix + ".tmp")
        temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        temporary.replace(arguments.output)
    print(json.dumps({"passed": report["passed"], "evidence": str(arguments.output), "error": report.get("error")}))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
