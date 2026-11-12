#!/usr/bin/env python3
"""Verify private local Prometheus/Grafana wiring without submitting jobs or waking workers."""

import argparse
import base64
import contextlib
import datetime as dt
import importlib.util
import json
import math
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("skulker_benchmark", ROOT / "scripts/benchmark-workers.py")
BENCHMARK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BENCHMARK)
SMOKE = BENCHMARK.SMOKE
STATES = {"PENDING", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"}
LABELS = {"job", "application", "namespace", "pod", "instance"}
DATASOURCE_UID = "skulker-prometheus"
DASHBOARD_UID = "skulker-operations"
ACTIVE_API = 'up{application="skulker-api"} == 1'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Authenticated checks must remain on their explicitly forwarded localhost endpoint.
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


class CheckError(RuntimeError):
    """A deliberately sanitized failure suitable for the evidence report."""

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def get_json(cluster, base, path, label, authorization=None):
    headers = {"Accept": "application/json"}
    if authorization is not None:
        headers["Authorization"] = authorization
    message = urllib.request.Request(base + path, headers=headers, method="GET")
    try:
        with HTTP.open(message, timeout=min(15, cluster.remaining())) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Never copy response bodies or request headers into logs/evidence: this request may be authenticated.
        raise CheckError(f"{label} returned HTTP {error.code}", error.code) from None
    except (urllib.error.URLError, OSError, json.JSONDecodeError):
        raise CheckError(f"{label} did not return a valid reachable JSON response") from None


def vector(cluster, prometheus, expression):
    response = get_json(
        cluster, prometheus, "/api/v1/query?" + urllib.parse.urlencode({"query": expression}),
        "Prometheus instant query",
    )
    if response.get("status") != "success" or response.get("data", {}).get("resultType") != "vector":
        raise CheckError("Prometheus did not return a successful instant vector")
    return response["data"]["result"]


def sample_value(sample):
    try:
        value = float(sample["value"][1])
    except (KeyError, IndexError, TypeError, ValueError):
        raise CheckError("Prometheus returned an invalid gauge sample") from None
    if not math.isfinite(value):
        raise CheckError("Prometheus returned a nonfinite gauge sample")
    return value


def check_prometheus(cluster, prometheus, report):
    response = get_json(cluster, prometheus, "/api/v1/targets?state=active", "Prometheus scrape targets")
    if response.get("status") != "success":
        raise CheckError("Prometheus scrape target discovery did not succeed")
    targets = response.get("data", {}).get("activeTargets", [])
    relevant = [target for target in targets if (
        target.get("labels", {}).get("application") == "skulker-api"
        or target.get("labels", {}).get("job") == "skulker-rabbitmq"
    )]
    report["prometheus"] = {
        "targets": [{
            "labels": {key: value for key, value in target.get("labels", {}).items() if key in LABELS},
            "healthy": target.get("health") == "up",
        } for target in relevant],
        "healthy": False,
    }
    api = [target for target in relevant if target.get("labels", {}).get("application") == "skulker-api"]
    rabbit = [target for target in relevant if target.get("labels", {}).get("job") == "skulker-rabbitmq"]
    if not api or not rabbit or any(target.get("health") != "up" for target in relevant):
        raise CheckError("Expected healthy API and RabbitMQ scrape targets")

    availability = vector(
        cluster, prometheus,
        'skulker_store_metrics_up{application="skulker-api"} and on(namespace,pod) (' + ACTIVE_API + ')',
    )
    expected_pods = {(target["labels"].get("namespace"), target["labels"].get("pod")) for target in api}
    available_pods = {(sample["metric"].get("namespace"), sample["metric"].get("pod")) for sample in availability}
    if available_pods != expected_pods or any(sample_value(sample) != 1 for sample in availability):
        raise CheckError("Durable store metrics are not healthy for every active API target")
    samples = vector(
        cluster, prometheus,
        'skulker_jobs{application="skulker-api"} and on(namespace,pod) (' + ACTIVE_API + ')',
    )
    grouped = {}
    for sample in samples:
        if sample_value(sample) < 0:
            raise CheckError("A durable job count is negative")
        labels = sample.get("metric", {})
        grouped.setdefault((labels.get("namespace"), labels.get("pod")), set()).add(labels.get("status"))
    if set(grouped) != expected_pods or any(statuses != STATES for statuses in grouped.values()):
        raise CheckError("Expected all six durable job status gauges for every active API target")
    report["prometheus"].update({
        "healthy": True, "durableStoreMetricsUp": True,
        "durableStatuses": sorted(STATES), "durableStatusSampleCount": len(samples),
    })


def authorization_from_secret(cluster, name):
    # The Secret exists only in memory; neither its data nor the resulting header enters evidence.
    try:
        secret = cluster.resource("secret", name)
        encoded = secret["data"]["admin-password"]
        password = base64.b64decode(encoded, validate=True).decode("utf-8")
    except Exception:
        raise CheckError("Grafana admin credentials could not be read from the owned Kubernetes Secret") from None
    if not password:
        raise CheckError("Grafana admin password is empty")
    return "Basic " + base64.b64encode(("admin:" + password).encode("utf-8")).decode("ascii")


def panel_count(panels):
    return sum((0 if panel.get("type") == "row" else 1) + panel_count(panel.get("panels", [])) for panel in panels)


def check_grafana(cluster, grafana, authorization, report):
    health = get_json(cluster, grafana, "/api/health", "Grafana health", authorization)
    if health.get("database") != "ok":
        raise CheckError("Grafana database health is not OK")
    datasource = get_json(
        cluster, grafana, f"/api/datasources/uid/{DATASOURCE_UID}/health",
        "Grafana Prometheus datasource health", authorization,
    )
    if datasource.get("status") != "OK":
        raise CheckError("Grafana Prometheus datasource health is not OK")
    try:
        response = get_json(
            cluster, grafana,
            f"/apis/dashboard.grafana.app/v1/namespaces/default/dashboards/{DASHBOARD_UID}",
            "Grafana operations dashboard", authorization,
        )
        dashboard = response.get("spec", {})
        uid = response.get("metadata", {}).get("name")
    except CheckError as error:
        if error.status not in (404, 410):
            raise
        response = get_json(
            cluster, grafana, f"/api/dashboards/uid/{DASHBOARD_UID}",
            "Grafana operations dashboard", authorization,
        )
        dashboard = response.get("dashboard", {})
        uid = dashboard.get("uid")
    panels = panel_count(dashboard.get("panels", []))
    if uid != DASHBOARD_UID or panels < 12:
        raise CheckError("Expected the Skulker operations dashboard with at least 12 panels")
    report["grafana"] = {
        "healthy": True, "databaseHealthy": True,
        "datasource": {"uid": DATASOURCE_UID, "healthy": True},
        "dashboard": {"uid": DASHBOARD_UID, "panelCount": panels},
    }


def wait_for(cluster, check, label):
    last_error = "not yet available"
    while True:
        try:
            cluster.remaining()
            check()
            return
        except CheckError as error:
            last_error = str(error)
        except TimeoutError:
            raise CheckError(f"Timed out waiting for {label}: {last_error}") from None
        try:
            time.sleep(min(1, cluster.remaining()))
        except TimeoutError:
            raise CheckError(f"Timed out waiting for {label}: {last_error}") from None


def save_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--prometheus-port", type=int, default=19090)
    parser.add_argument("--grafana-port", type=int, default=13000)
    parser.add_argument("--grafana-secret", default="skulker-grafana-credentials")
    parser.add_argument("--output", type=Path, default=ROOT / ".tooling/monitoring-smoke.json")
    arguments = parser.parse_args()
    if arguments.timeout_seconds <= 0:
        parser.error("timeout must be positive")
    if arguments.prometheus_port == arguments.grafana_port or any(
        not 1024 <= port <= 65535 for port in (arguments.prometheus_port, arguments.grafana_port)
    ):
        parser.error("distinct localhost ports in 1024..65535 are required")
    report = {
        "context": SMOKE.CONTEXT, "namespace": SMOKE.NAMESPACE,
        "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "passed": False, "errors": [],
    }
    try:
        cluster = SMOKE.Cluster(arguments.timeout_seconds)
        for service in ("skulker-prometheus", "skulker-grafana"):
            resource = cluster.resource("service", service)
            if resource["metadata"].get("annotations", {}).get("meta.helm.sh/release-name") != SMOKE.RELEASE:
                raise CheckError("Refusing to forward monitoring service outside the Skulker release")
        with contextlib.ExitStack() as stack:
            prometheus = stack.enter_context(BENCHMARK.forward(cluster, "skulker-prometheus", 9090, arguments.prometheus_port))
            grafana = stack.enter_context(BENCHMARK.forward(cluster, "skulker-grafana", 3000, arguments.grafana_port))
            authorization = authorization_from_secret(cluster, arguments.grafana_secret)
            wait_for(cluster, lambda: check_prometheus(cluster, prometheus, report), "Prometheus monitoring")
            wait_for(cluster, lambda: check_grafana(cluster, grafana, authorization, report), "Grafana provisioning")
        report["passed"] = True
    except CheckError as error:
        report["errors"].append(str(error))
    except KeyboardInterrupt:
        report["errors"].append("Monitoring verification was interrupted")
    except TimeoutError:
        report["errors"].append("Monitoring verification exceeded its deadline")
    except Exception as error:
        # Imported subprocess helpers can include command output in exceptions; never persist it here.
        report["errors"].append(f"{type(error).__name__}: local monitoring verification failed")
    finally:
        report["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        save_report(arguments.output, report)
    print(json.dumps({"passed": report["passed"], "evidence": str(arguments.output), "errors": report["errors"]}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
