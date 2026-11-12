# Unprovisioned AWS foundation

This directory defines a disposable AWS foundation. It has not been applied or tested against an AWS account. No real AWS plan, credential check, cluster creation, or application deployment is part of this milestone.

- `modules/network` creates a DNS-enabled VPC, two public subnets in distinct availability zones, an Internet Gateway, and public routes. There are no NAT gateways, load balancers, or SSH ingress resources.
- `modules/eks` creates an EKS 1.36 cluster with a private endpoint and an operator-CIDR-restricted public endpoint, an explicit administrator access entry, and one managed node group. Nodes use AL2023 x86_64, IMDSv2 with hop limit one, encrypted gp3 disks, and standard T3 credits. Desired/minimum node counts are one; maximum is two. No Cluster Autoscaler is installed.
- `modules/storage` creates private, versioned, SSE-S3-encrypted object storage with a TLS-only bucket policy and no force destruction. A Pod Identity role grants access only to `datasets/` objects; its trust restricts the cluster, namespace, and service account. No application S3 adapter exists yet.
- `environments/aws` wires the modules and enforces the expected account ID through the AWS provider. Terraform 1.17.2 and AWS provider 6.72.0 are pinned. The provider dependency lock must be committed; state and real variable files must not.

The CNI has a separate IRSA role restricted to `kube-system/aws-node` and the STS audience. This lets networking bootstrap before the Pod Identity Agent runs. Node permissions are limited to the recommended worker-node and ECR pull policies. The Pod Identity Agent is installed after nodes become ready. CNI, Pod Identity Agent, and AL2023 AMI release versions are **required explicit inputs**: their regional EKS 1.36 compatibility must be resolved and reviewed before a future cloud plan. Synthetic mock versions are not deployable version recommendations.

The infrastructure uses public node IPs to avoid NAT cost, while EKS-managed security groups provide node/control-plane connectivity. The public Kubernetes endpoint accepts only one through four operator IPv4 CIDRs, each `/24` or narrower; use a `/32` whenever possible. Cluster-creator administrator access is disabled. The provided administrator must be an IAM role in the expected account. Control-plane API, audit, and authenticator logs retain seven days in CloudWatch.

## Credential-free validation

From the repository root:

```sh
scripts/bootstrap-terraform.sh
scripts/verify-terraform.sh
```

The verifier checks formatting, initializes the public provider dependency without backend initialization, validates schemas, and executes `environments/aws/tests/foundation.tftest.hcl`. Every test run is a provider-mocked **plan**: it creates no cloud resources and does not call AWS. Mock tests check topology, access boundaries, encrypted storage, node metadata, version pins, scoped IAM, and unsafe-input rejection. They cannot establish regional service availability, quota, IAM authorization, actual addon/AMI compatibility, or runtime readiness.

## Future inputs and outputs

Seven inputs have no default: `expected_account_id`, `admin_role_arn`, `operator_ipv4_cidrs`, `bucket_name`, `node_ami_release_version`, `vpc_cni_addon_version`, and `pod_identity_agent_addon_version`. Review the nondeployable example before preparing private variables. Root outputs include the cluster name/endpoint, dataset bucket name, dataset workload role ARN, and networking/EKS/storage contract summaries. No secrets are Terraform inputs or outputs.

The root uses a local state backend by default. Before a future authorized deployment, establish private encrypted remote state and locking, review the exact state/account/region, and set a budget and teardown plan. This dataset bucket is not a preconfigured Terraform state backend. Provisioning, Kubernetes application deployment, EBS CSI/cloud PVC configuration, image publication, and the application S3 adapter remain separate work.

Versioned S3 data is intentionally retained: `force_destroy=false` prevents silently deleting populated buckets. A future teardown must separately decide what to retain or export and verify old object versions, delete markers, EBS volumes, ENIs, public addresses, log groups, and any externally created load balancers after destruction. See the repository AWS guide for estimates and explicit approval requirements.

Sources: [Terraform releases](https://releases.hashicorp.com/terraform/), [AWS provider 6.72.0](https://github.com/hashicorp/terraform-provider-aws/releases/tag/v6.72.0), [EKS support calendar](https://docs.aws.amazon.com/eks/latest/userguide/kubernetes-versions.html), [Pod Identity trust conditions](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-role.html), [Terraform provider mocking](https://developer.hashicorp.com/terraform/language/tests/mocking).
