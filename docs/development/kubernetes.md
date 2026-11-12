# Local Kubernetes development

This milestone runs Skulker on a disposable local kind cluster. PostgreSQL remains
authoritative for jobs and results; RabbitMQ transports durable work; KEDA adjusts
worker replicas from queue depth. No AWS credentials or cloud resources are involved.

## Prerequisites

Use Docker with Linux containers and enough available CPU, memory, and disk for the
API, PostgreSQL, RabbitMQ, KEDA, and up to eight Java workers. Each application pod
has resource requests and limits; check pending-pod events if the host cannot fit
the configured replicas. A running Docker daemon is required.

The pinned local toolchain is kind `0.33.0`, kubectl `1.36.6`, Helm `4.3.1`, KEDA
`2.21.0`, and kind node image `kindest/node:v1.36.4`. Python 3 uses only its standard
library for the smoke and scaling checks. Place tools on `PATH` or in the ignored
`.tooling/bin/` directory. The setup script verifies the downloaded KEDA chart
against its pinned checksum.

Install the checksum-verified tools into the project directory without changing global
packages:

```sh
scripts/bootstrap-deployment-tools.sh
export PATH="$PWD/.tooling/bin:$PATH"
```

This builds the development tools image and extracts kind, kubectl, and Helm. It requires
Docker and network access for the first build. `DOCKER_COMMAND=docker.exe` supports the
Windows CLI fallback; native WSL Docker works once Desktop integration is enabled.

The scripts default `KUBECONFIG` to the ignored repository file
`.tooling/kubeconfig`. All cluster operations explicitly target `kind-skulker`.
The namespace and Helm release are both `skulker`; the scripts guard ownership
before changing or deleting resources. They do not switch to another cluster to
make a command succeed.

## Build and deploy

From the repository root:

```sh
scripts/build-images.sh
scripts/local-cluster.sh create
scripts/local-cluster.sh deploy
scripts/local-cluster.sh status
```

`build-images.sh` builds the API and worker images. `SKULKER_IMAGE_TAG` defaults to
`local`. Set `DOCKER_COMMAND=docker.exe` when using the Docker Desktop CLI from WSL
and a native `docker` executable is unavailable. The cluster script loads those
images into kind, installs the pinned KEDA release, then installs the Skulker chart
from `infrastructure/helm/skulker`.

Deployment generates local PostgreSQL and RabbitMQ credentials in the
`skulker-credentials` Secret only when it is absent. Upgrades reuse that Secret;
they must not rotate passwords independently of the existing database volumes.
Do not print or commit Secret contents. Workload manifests reference Secret keys;
application passwords are not literal Helm values checked into Git.
The RabbitMQ URI uses the full `skulker-rabbitmq.skulker.svc.cluster.local` address because
KEDA runs in its own namespace. Database startup retries tolerate initial service DNS/readiness
delays. Each application pool caps at five connections with one idle connection, keeping
eight worker replicas within the local PostgreSQL connection budget.

Services use ClusterIP networking. No ingress, cloud load balancer, or public API
endpoint is created. To inspect the API locally:

```sh
export KUBECONFIG="$PWD/.tooling/kubeconfig"
kubectl --context kind-skulker -n skulker port-forward svc/skulker-api 18080:8080 --address=127.0.0.1
```

Use `http://127.0.0.1:18080/actuator/health/readiness` for API readiness.
Liveness uses `/actuator/health/liveness`. Kubernetes probes, resource bounds, and
restricted application security contexts belong to the Helm workload definitions.

## Validate behavior and scale-up

The smoke tool verifies workload ownership, probes, CPU/memory requests and limits,
API rollout and health, dataset upload, asynchronous job execution, and the stored
backtest result:

```sh
python3 scripts/kubernetes-smoke.py --smoke-only
```

Its six-bar synthetic fixture must execute one buy at the next observed open:
nine shares at price `10`, fee `1`, and final portfolio value `27`. A success response
alone does not satisfy this check.

Run the explicit scaling acceptance check separately:

```sh
python3 scripts/kubernetes-smoke.py --autoscale --timeout-seconds 900
```

This check first requires no existing active work, verifies a worker ScaledObject
with minimum zero and maximum eight, and scales only the owned worker deployment
to zero. It waits for all worker pods to disappear before submitting work. Every
subsequent scale-up must come from KEDA; the tool never manually scales up.

The tool uploads a deterministic 10,000-bar synthetic dataset and submits 100-job
parameter batches. It can submit up to five batches to sustain a backlog while
workers start. Calendar dates are observed bars, including weekends; the fixture
does not imply an exchange trading calendar or a profitable strategy. It records
RabbitMQ ready/unacknowledged counts and actual Ready worker pods while waiting
for at least two Ready workers and successful completion of every submitted task.
Desired replicas alone do not pass the check. This acceptance check is not the
Milestone 5 throughput benchmark or proof of linear scaling.

Evidence is written atomically to the ignored `.tooling/kubernetes-smoke.json`,
including partial observations and an error when a check fails. The default mode
is smoke only. `--local-port` changes the localhost port; `--output` selects a report
path. An occupied local port fails rather than querying a different process.
The port-forward subprocess is stopped on success, failure, and interruption.

Large synthetic results consume local PostgreSQL disk space. The tool retains its
datasets, jobs, batches, and results for inspection. Use a disposable environment;
it does not delete existing data to make an acceptance check pass. No measured
results are claimed here until the actual cluster checks have run; record executed
commands and their observed outcomes in [verification.md](verification.md).

## Diagnose and tear down

Inspect owned resources without changing context:

```sh
kubectl --context kind-skulker -n skulker get pods,hpa,scaledobject
kubectl --context kind-skulker -n skulker get events --sort-by=.lastTimestamp
kubectl --context kind-skulker -n skulker logs deployment/skulker-api
kubectl --context kind-skulker -n skulker logs deployment/skulker-worker
```

Check image loading, readiness failures, resource pressure, and KEDA/HPA conditions
before attributing a stalled queue to application execution. Queue-depth collection
uses `rabbitmqctl` inside the owned RabbitMQ pod and does not read credentials.
If using a separate tools container, attach it to Docker's kind network and obtain
kind's internal kubeconfig into the owned `KUBECONFIG`; retain TLS verification.

```sh
scripts/local-cluster.sh stop
scripts/local-cluster.sh delete
```

`stop` removes only the Skulker Helm release; it retains the externally created
Secret and chart-kept PostgreSQL volumes, and leaves KEDA and the cluster running.
`delete` removes only the guarded `skulker` kind cluster and its local data. Inspect
the script output and Docker container list afterward to confirm that the owned
cluster was removed. These commands never provision or destroy AWS resources.
