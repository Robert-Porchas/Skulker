# ADR-005: Local-first infrastructure

Status: **Proposed** for infrastructure milestones; not implemented.

## Context

The project should demonstrate deployment and scaling without making cloud cost or credentials prerequisites for local development.

## Proposed decision

Introduce Compose for local service dependencies when services exist. Later use kind and Helm for local Kubernetes, with KEDA scaling workers from RabbitMQ queue depth. Measure performance and resource usage at multiple worker counts before drawing scaling conclusions.

Keep optional AWS Terraform deployment separate from local workflows. Require explicit authorization, a cost estimate, and a teardown plan before creating billable resources. Verify remaining billable resources after teardown rather than assuming a successful destroy command proves everything is gone.

## Consequences

The initial Java library requires none of Docker, Kubernetes, Terraform, or AWS. Later local deployments will need realistic resource limits and failure testing; local success alone does not prove cloud operability.
