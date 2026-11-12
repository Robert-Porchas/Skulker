# Optional AWS foundation

This milestone prepares an **unprovisioned Terraform foundation**. No AWS account has been connected, no cloud resources have been created, and Skulker has not been deployed to EKS. Local kind remains the verified application deployment.

The foundation defines networking, EKS, IAM, and a private versioned S3 bucket. It does not install the application Helm release, PostgreSQL, RabbitMQ, KEDA, monitoring, an ingress, a load balancer, or a storage driver. Dataset CSV content and results still live in PostgreSQL; the S3 bucket and its workload role are reserved for a future storage adapter.

## Validate without AWS credentials

Run from the repository root on Linux with Bash, curl, Python 3, and sha256sum:

```sh
bash scripts/bootstrap-terraform.sh
bash scripts/verify-terraform.sh
```

The bootstrap checks a pinned SHA-256 before installing Terraform 1.17.2 into ignored `.tooling/bin`. The verification script checks formatting, initializes providers without backend initialization and with the dependency lock file read-only, validates configuration, and runs only `tests/foundation.tftest.hcl` with a mocked AWS provider and plan assertions. Initialization downloads public provider artifacts; it does not require an AWS account. The script removes AWS credential/profile environment variables, disables instance metadata, and points credential files at `/dev/null`.

These checks establish configuration consistency and expected planned resources. They do not establish account permissions, regional capacity, EKS add-on compatibility, network connectivity, successful provisioning, or application behavior on AWS. Record actual command results in the [verification log](verification.md); do not report mocked plans as an AWS deployment.

Terraform sources are under `infrastructure/terraform/environments/aws`, with `network`, `eks`, and `storage` modules. Commit the provider dependency lock file; keep provider caches, state, saved plans, and operator configuration out of Git.

## Defaults and boundaries

| Setting | Foundation default or required input |
| --- | --- |
| Region and availability zones | `us-east-1`, `us-east-1a`, `us-east-1b` |
| Network | `10.42.0.0/16`, two public subnets, internet gateway, no NAT gateway |
| EKS | Kubernetes `1.36`, private API access enabled and public API restricted to explicit operator CIDRs |
| Nodes | Linux `t3.medium`; minimum/desired/maximum `1/1/2` |
| Node disks | Encrypted 20 GiB gp3 root volume per node |
| Node identity | IMDSv2 with hop limit 1; CNI has a separate IRSA role |
| CPU credits | T3 standard mode; exhausted credits can throttle sustained compute |
| Control-plane logs | API, audit, and authenticator logs; seven-day retention |
| Dataset bucket | Private, versioned, SSE-S3 encryption, TLS required, `force_destroy = false` |
| Future storage access | Dedicated Pod Identity role limited to the configured `datasets/*` prefix |
| Required inputs | `expected_account_id`, `operator_ipv4_cidrs`, `admin_role_arn`, `bucket_name`, `node_ami_release_version`, `vpc_cni_addon_version`, `pod_identity_agent_addon_version` |

Two subnets permit placement across two availability zones; one desired node does not provide application high availability. The node-group maximum is a bound, not automatic node scaling: no Cluster Autoscaler or Karpenter is installed. KEDA scales worker pods only after a separately approved application deployment and does not add EC2 capacity.

The public-node design avoids NAT hourly charges while permitting image pulls and AWS API access. Nodes receive public IPv4 addresses; there is no SSH configuration or application exposure in this foundation. Public EKS access is restricted to the operator's actual egress CIDR, usually a `/32`. Private endpoint access lets nodes reach the control plane without adding every node's public address to that allowlist. Review [AWS endpoint guidance](https://docs.aws.amazon.com/eks/latest/userguide/config-cluster-endpoint.html) before changing this arrangement.

The administrator role must be an explicit IAM role in the intended account. It is separate from the cluster/node service roles and the future dataset workload role. The resource-name prefix defaults to `skulker-research`; the dataset Pod Identity association defaults to namespace/service account `skulker/skulker` and prefix `datasets/`. Provider account restrictions are an additional guard, not authorization to create resources. Review every IAM policy and trust relationship in the saved plan.

Kubernetes 1.36 is currently listed under [EKS standard support](https://docs.aws.amazon.com/eks/latest/userguide/kubernetes-versions.html). Recheck support dates before a real plan. Supply a reviewed AL2023 x86_64 AMI release matching that minor and explicit compatible `eksbuild` versions for the VPC CNI and Pod Identity Agent; no unverified `latest` selections are supplied. Mock-test version strings are fixtures, not availability evidence. The existing kind chart cannot be treated as a complete AWS deployment: persistent volumes need an approved EBS CSI/IAM/storage-class design, and image distribution, database durability, backups, credentials, application exposure, and cloud monitoring remain deployment work.

## Cost estimate before any provisioning

Prices checked **2026-11-12 UTC**, in USD for `us-east-1`. This is a list-price budget, with no free-tier credits, discounts, commitments, taxes, or support-plan fees applied.

| Resource | Budget rate | Official source |
| --- | --- | --- |
| EKS control plane under standard support | $0.10 per cluster-hour | [EKS pricing](https://aws.amazon.com/eks/pricing/) |
| Linux On-Demand `t3.medium` | $0.0416 per node-hour | [AWS regional EC2 price catalog](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/US%20East%20%28N.%20Virginia%29/Linux/index.json) |
| gp3 root storage at baseline IOPS/throughput | $0.08 per GiB-month | [AWS gp3 pricing guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/optimize-costs-microsoft-workloads/ebs-migrate-gp2-gp3.html) |
| One public IPv4 address per node | $0.005 per address-hour | [VPC IPv4 pricing](https://aws.amazon.com/vpc/pricing/) |

The EC2 catalog was published on 2026-11-10; its Linux `t3.medium` entry is SKU `NN4EGUUQRWVYP98C`. It resolves the older $0.0418 figure still shown on the T3 marketing page. Refresh the catalog before spending money.

For `H` hours and `N` continuously running nodes, use this 730-hour budget convention:

```text
base(H, N) = 0.10 * H
           + N * (0.0416 * H + 0.005 * H + 20 * 0.08 * H / 730)
```

| Scenario | 24 hours | 730 hours |
| --- | ---: | ---: |
| Default: one node, one address, 20 GiB root disk | $3.57 | $108.62 |
| Two nodes, two addresses, 40 GiB total root disks | $4.74 | $144.24 |

For one node over 730 hours, the components are $73.00 EKS, $30.368 EC2, $3.65 IPv4, and $1.60 gp3. Storage is prorated using `H/730` for this comparison; actual billing uses the applicable calendar and service billing rules. Scaling worker pods to zero leaves these foundation charges running. The foundation requests the `STANDARD` upgrade policy; this does not freeze an unsupported minor indefinitely. If that policy is changed to permit extended support, its $0.60 per cluster-hour rate would add $12.00 per day or $365.00 per 730 hours over this estimate. [EKS pricing](https://aws.amazon.com/eks/pricing/)

Add usage charges separately:

- CloudWatch Standard log ingestion: approximately `0.50 * ingested_GB`; retained compressed storage: `0.03 * retained_GB_month`. Seven-day retention bounds retained history, not ingestion. Queries and optional observability features add charges. [CloudWatch pricing](https://aws.amazon.com/cloudwatch/pricing/)
- S3 Standard, first 50 TiB: `0.023 * stored_GiB_month + 0.005 * PUT_COPY_POST_LIST_requests / 1000 + 0.0004 * GET_requests / 1000`. Count retained object versions, not only current objects. Rates were verified in the [regional S3 catalog](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/us-east-1/index.json), published 2026-11-09; [S3 pricing](https://aws.amazon.com/s3/pricing/) defines its binary storage units.

The base estimate excludes data transfer, cross-AZ traffic, extra disks and snapshots, object versions, log usage, registry storage, load balancers, NAT, VPC interface endpoints, managed databases, backup services, and any later application resources. The default T3 standard credit mode avoids surplus-credit billing but does not guarantee sustained benchmark throughput. Do not extrapolate the local benchmark to this node group.

Before provisioning, agree on an account/region, maximum lifetime, owner, refreshed itemized estimate, budget alerts, and teardown time. Alerts report spending and are not a spending cap.

## Future provisioning requires a separate approval

No commands in this section have been run against AWS. Offline validation is the current scope.

For a future deployment, use short-lived IAM Identity Center or assumed-role credentials in a named profile. Do not paste access keys into Terraform files, shell history, chat, or Git. Put reviewed values in ignored `.tooling/aws.tfvars`, including all required inputs above: the exact expected account ID, administrator role ARN, globally unique bucket name, operator egress CIDRs, AMI release, and two compatible add-on versions. Operator CIDRs allow one through four IPv4 ranges, each `/24` or narrower; prefer a single known `/32`. Confirm the active caller's account and role before planning; recheck the provider's `allowed_account_ids` guard.

A real `terraform plan` reads AWS APIs and requires configured credentials for the intended account. This delivery runs only mock plans. For future cloud work, initialize the intended state backend first, then prepare a saved plan using absolute paths from the repository root:

```sh
export PATH="$PWD/.tooling/bin:$PATH"
export AWS_PROFILE=skulker-dev
terraform -chdir=infrastructure/terraform/environments/aws plan \
  -var-file="$PWD/.tooling/aws.tfvars" -out="$PWD/.tooling/aws.plan"
terraform -chdir=infrastructure/terraform/environments/aws show "$PWD/.tooling/aws.plan"
```

Review the exact saved artifact, resource count, IAM access, account/region, endpoint CIDRs, logging, storage retention, and cost/lifetime agreement. **Do not apply until the user explicitly approves provisioning that reviewed plan.** A saved-plan apply can run without Terraform's usual interactive confirmation; it is not an authorization substitute. Application Helm installation and registry pushes require their own reviewed scope.

After a future approved apply, the root outputs are `cluster_name`, `cluster_endpoint`, `dataset_bucket_name`, `dataset_workload_role_arn`, and the `network_contract`, `eks_contract`, and `storage_contract` summaries. Record these for handoff and teardown; they do not create a kubeconfig or install Kubernetes resources. Use a dedicated AWS kubeconfig and explicit AWS context for any later access, keeping the owned kind context untouched.

The root currently uses local state. `init -backend=false` during offline verification does not establish durable shared state. Before real shared provisioning, separately bootstrap an access-controlled encrypted/versioned state backend with locking, establish recovery ownership, and review migration. The dataset bucket is not the Terraform backend. State and saved plans can contain sensitive values: keep them outside Git and PR artifacts. Never destroy a state backend before the infrastructure it records has been reconciled.

## Teardown and remaining-charge verification

Deletion also requires explicit approval. Preserve state and inventory identifiers until verification is complete; never interpret a successful `terraform destroy` as proof that every billable resource is gone.

1. Confirm the AWS account, region, cluster identity, and dedicated AWS kubeconfig. The local kind helpers must continue to reject AWS contexts. Inventory Kubernetes Services, Ingresses, PVCs, PVs, and installed controllers before changing anything.
2. If workloads were installed later, stop submissions and drain workers. Decide which database data, object versions, and snapshots must be exported or retained. Review the data-loss consequences separately.
3. Delete approved LoadBalancer Services and Ingresses while their controllers and IAM permissions still exist. Wait for AWS load balancers, target groups, related ENIs/security groups, and addresses to disappear. [AWS requires load-balancer cleanup before cluster deletion](https://docs.aws.amazon.com/eks/latest/APIReference/API_DeleteCluster.html).
4. Review PVC/PV reclaim policies, CSI volumes, snapshots, and retained Helm PVCs. Delete approved claims while the CSI controller is available, then verify the corresponding EBS resources. A `Retain` policy or snapshot intentionally survives Kubernetes deletion.
5. Review the S3 bucket's current objects, noncurrent versions, delete markers, and incomplete multipart uploads. `force_destroy = false` deliberately prevents silently emptying a nonempty bucket. Export or explicitly approve version deletion; deleting current keys alone does not remove retained versions. [S3 version deletion behavior](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeletingObjectVersions.html)
6. Prepare and review a saved Terraform destroy plan with the same account, variables, and state backend. Obtain approval before applying it. Let Terraform remove managed nodes, cluster resources, IAM, and networking in dependency order; do not bypass errors with broad manual deletion.
7. Reconcile actual AWS inventory against the recorded resource IDs and tags. Check EKS clusters/node groups, EC2 instances and Auto Scaling groups, EBS volumes/snapshots, load balancers, target groups, ENIs, NAT gateways, EIPs/public IPv4 allocations, and regional resources in every region used. Check S3 versions/uploads, CloudWatch log groups, ECR images, backups, and any separately created endpoints or monitoring services. Retained resources need an owner and continuing-cost decision.
8. Check AWS Billing/Cost Explorer after usage records have caught up, then check again on the following billing update. Verify remaining resources directly as well: billing data can lag and a zero current chart is not resource deletion evidence. Record what was deleted, intentionally retained, or still unresolved. Remove or migrate the state backend only after this reconciliation.

This foundation has not incurred AWS charges because it has not been provisioned. Its acceptance gate is offline validation plus reviewed code, cost, and teardown documentation; a future cloud deployment needs its own runtime acceptance evidence.
