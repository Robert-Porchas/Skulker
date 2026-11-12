# Skulker local Helm chart

This self-contained development chart deploys API and worker applications, one PostgreSQL instance, one RabbitMQ instance, and optional KEDA worker scaling. It has no dependency on third-party PostgreSQL or RabbitMQ charts. Install KEDA separately before enabling autoscaling; this chart does not install its CRDs or operator.

The standard release and namespace are both `skulker`. Component names are `<release>-api`, `<release>-worker`, `<release>-postgres`, and `<release>-rabbitmq`. Pod selectors use `app.kubernetes.io/instance=<release>` and `app.kubernetes.io/component=api|worker|postgres|rabbitmq`. PostgreSQL and RabbitMQ use headless ClusterIP Services as StatefulSet governing services; application Services use normal ClusterIPs. There is no ingress, public load balancer, or host port.

## Images and credentials

Load the locally built `skulker-api:local` and `skulker-worker:local` images into kind before deployment, or override `api.image.repository/tag` and `worker.image.repository/tag`. `image.digest` is optional and, when present, appends the immutable digest after the tag. The RabbitMQ default includes its verified image digest.

Create `existingSecret` (default `skulker-credentials`) in the release namespace before starting workloads. Required keys are `db-password`, `rabbit-password`, and `rabbit-uri`; no secret resource or credential value is rendered by the chart. KEDA runs in a separate namespace, so the URI must use the namespace-qualified RabbitMQ service hostname and percent-encode credentials and the default virtual host, for example `amqp://skulker:<encoded-password>@skulker-rabbitmq.skulker.svc.cluster.local:5672/%2f`. The deployment script creates this Secret without committing credentials.

Changing initialization passwords in a Secret does not change accounts already stored in PostgreSQL or RabbitMQ. Preserve credentials with their PVCs, or perform an explicit account-password update before changing the Secret.

## Health and worker scaling

The API startup probe calls `/actuator/health`, requiring PostgreSQL and RabbitMQ initially. The RabbitMQ health check also opens the connection that declares task queues before scale-to-zero operation. After startup, API readiness includes only `readinessState,db`, so broker disruption can leave the API available to accept durable jobs. Worker readiness includes `readinessState,db,rabbit`. Both applications' liveness checks include only `livenessState`; dependency outages alone do not trigger liveness restarts.

With `autoscaling.enabled=true`, Helm omits worker `spec.replicas` on installation and upgrades. Kubernetes may initially default to one replica before KEDA applies the minimum. Steady-state defaults are zero through eight workers, polling every five seconds, a 30-second cooldown, and a target of five ready RabbitMQ messages per worker. The AMQP queue-length scaler observes ready messages; it does not include unacknowledged work. Lease recovery and committed-result fencing protect interrupted work when workers stop. These defaults establish a local scaling setup, not a throughput claim. [KEDA RabbitMQ scaler](https://keda.sh/docs/2.21/scalers/rabbitmq-queue/).

Set `autoscaling.enabled=false` and `worker.replicas=N` for a fixed worker count. Keep the ScaledObject and manual scaling modes separate when measuring behavior. Application shutdown has a 90-second pod grace period and a 70-second Spring lifecycle phase budget.

## Local security and storage

All pods use a ServiceAccount without API-token mounting, drop Linux capabilities, prohibit privilege escalation, and use the runtime-default seccomp profile. Applications use UID/GID 10001, a read-only root filesystem, and a bounded writable `/tmp`. PostgreSQL uses its Alpine UID/GID 70 and a read-only root filesystem with writable data, socket, and temporary volumes. RabbitMQ uses UID 100/GID 101, verified from the official Alpine image; its root filesystem remains writable for broker configuration/runtime compatibility. These fixed broker IDs are tied to the selected Alpine images and must be reviewed when changing image families. [Official RabbitMQ Dockerfile](https://github.com/docker-library/rabbitmq/blob/0b2a146a189d6d604a77e5a474e3f12f46abdbe4/4.3/alpine/Dockerfile).

Default requests are 100m CPU and 256 MiB per pod; limits are one CPU and 512 MiB. PostgreSQL requests a 5 GiB PVC and RabbitMQ requests 1 GiB. An empty `persistence.storageClass` uses the cluster default StorageClass. Both StatefulSets retain PVCs after scaling and uninstall. This is a single-instance local setup without database or broker high availability; deleting the kind cluster also removes its local storage.

## Validation

```sh
helm lint infrastructure/helm/skulker
helm template skulker infrastructure/helm/skulker --namespace skulker
helm template skulker infrastructure/helm/skulker --namespace skulker --set autoscaling.enabled=false
```

`values.schema.json` rejects unknown values and malformed image, resource, storage, and scaling inputs. Template validation also rejects a minimum replica count above the maximum. Run cluster smoke verification after deploying; a successful render alone does not validate image permissions, probes, persistence, or autoscaling.
