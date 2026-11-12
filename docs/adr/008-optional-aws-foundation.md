# ADR-008: Optional AWS foundation without automatic provisioning

Status: **Accepted** for the unprovisioned foundation after integrated code review, thirteen mock-plan tests, and review of cost and teardown documentation. AWS deployment remains unperformed and separately authorized.

## Context

Skulker has a verified local Kubernetes deployment and measured worker benchmarks. AWS infrastructure should demonstrate explicit, reviewable boundaries without making credentials or ongoing cloud costs prerequisites for development. Terraform validation cannot establish that the application runs on EKS.

## Decision

Add an optional Terraform root with concrete network, EKS, and storage modules. Pin Terraform 1.17.2, AWS provider 6.72.0, and EKS Kubernetes 1.36. Validate formatting, schemas, and planned properties using a mocked provider without account credentials. Provisioning, application deployment, and destructive teardown require separate explicit authorization. Real planning uses credentials for a named account; no AWS plan is run in this delivery.

Use two public subnets across availability zones, an internet gateway, restricted public EKS operator access, and a private control-plane endpoint. A small managed node group starts with one `t3.medium` and has a maximum of two; no NAT gateway or node autoscaling controller is installed. Nodes use encrypted gp3 root disks, standard CPU credits, and IMDSv2. This is a disposable development foundation, not evidence of application high availability or production readiness.

Require the expected account ID and an explicit administrator role. Separate cluster/node service permissions, CNI networking permissions through IRSA, and future dataset workload permissions through Pod Identity. Use a private encrypted versioned S3 bucket with TLS enforcement, a constrained dataset prefix, and automatic bucket emptying disabled. Existing dataset and result persistence remains PostgreSQL; an S3 adapter is not implemented by creating a bucket.

Keep application Helm releases and cloud storage-driver integration outside this Terraform root. Do not couple infrastructure destruction to a Kubernetes provider that first requires a live cluster. Leave state-backend bootstrap and migration as an explicit prerequisite for future shared provisioning; never commit state or operator credentials.

## Consequences

Local build and deployment workflows remain independent of AWS. Mock plans provide a reproducible review gate while account permissions, capacity, add-ons, connectivity, image delivery, persistent volumes, and application behavior remain unverified on EKS.

The public-node/no-NAT choice reduces fixed development cost and permits outbound dependencies, while requiring restrictive network access and public IPv4 budgeting. One desired node is a cost choice, not a resilience guarantee. Standard T3 credits can limit sustained compute; local scaling measurements do not predict cloud throughput.

Versioned storage and retained application volumes require deliberate lifecycle decisions. A destroy plan does not cover every resource that a Kubernetes controller or operator could create, and retained data can keep accruing charges. The [AWS guide](../development/aws.md) records current source-backed cost calculations, approval boundaries, teardown order, and remaining-resource/billing checks.

Acceptance approves the architectural boundary; it does not claim that AWS resources were provisioned or authorize a future apply.
