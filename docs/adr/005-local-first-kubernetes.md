# ADR-005: Local-first infrastructure

Status: **Accepted** and verified for local Kubernetes in Milestone 4.

## Context

The project should demonstrate deployment and scaling without making cloud cost or credentials prerequisites for local development.

## Decision

Use Compose for local service dependencies and kind/Helm for local Kubernetes, with KEDA scaling workers from RabbitMQ queue depth. The three-node local deployment and queue-driven scale-up/down have been verified. Measure performance and resource usage at controlled worker counts in Milestone 5 before drawing scaling conclusions.

Keep optional AWS Terraform deployment separate from local workflows. Require explicit authorization, a cost estimate, and a teardown plan before creating billable resources. Verify remaining billable resources after teardown rather than assuming a successful destroy command proves everything is gone.

## Consequences

The standalone financial library requires none of Docker, Kubernetes, Terraform, or AWS. Local deployments use nonroot identities, bounded resources, private Services, probes, persistent development storage, and generated external Secrets. Scripts check context and local cluster CA before mutations. Local success does not establish cloud operability.
