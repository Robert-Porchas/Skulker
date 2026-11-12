# All runs are mocked plans. No provider aliases, real plans, credentials, or AWS API calls.
mock_provider "aws" {
  override_during = plan
  mock_resource "aws_vpc" { defaults = { id = "vpc-0123456789abcdef0" } }
  mock_resource "aws_subnet" { defaults = { id = "subnet-0123456789abcdef0" } }
  mock_resource "aws_internet_gateway" { defaults = { id = "igw-0123456789abcdef0" } }
  mock_resource "aws_iam_role" { defaults = { arn = "arn:aws:iam::111122223333:role/mock-role" } }
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::111122223333:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/MOCK" }
  }
  mock_resource "aws_launch_template" {
    defaults = { id = "lt-0123456789abcdef0", latest_version = 1 }
  }
  mock_resource "aws_eks_cluster" {
    defaults = {
      arn        = "arn:aws:eks:us-east-1:111122223333:cluster/skulker-research"
      endpoint   = "https://mock.eks.invalid"
      identity   = [{ oidc = [{ issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/MOCK" }] }]
      vpc_config = { cluster_security_group_id = "sg-0123456789abcdef0" }
    }
  }
  mock_resource "aws_s3_bucket" {
    defaults = {
      id  = "skulker-mock-datasets-111122223333"
      arn = "arn:aws:s3:::skulker-mock-datasets-111122223333"
    }
  }
}

variables {
  expected_account_id = "111122223333"
  admin_role_arn      = "arn:aws:iam::111122223333:role/SkulkerOperator"
  operator_ipv4_cidrs = ["203.0.113.42/32"]
  bucket_name         = "skulker-mock-datasets-111122223333"
  # Syntactically valid synthetic pins only; mocks do not establish regional compatibility.
  node_ami_release_version         = "1.36.0-20260101"
  vpc_cni_addon_version            = "v1.0.0-eksbuild.1"
  pod_identity_agent_addon_version = "v1.0.0-eksbuild.1"
}

run "reviewed_foundation_contract" {
  command = plan
  assert {
    condition     = output.network_contract.subnet_count == 2 && length(distinct(output.network_contract.availability_zones)) == 2 && alltrue(output.network_contract.public_ips)
    error_message = "Exactly two public subnets in distinct AZs must support public-IP nodes without NAT."
  }
  assert {
    condition     = output.network_contract.dns_support && output.network_contract.dns_hostnames && length(output.network_contract.default_route) == 1 && alltrue([for route in output.network_contract.default_route : route.cidr_block == "0.0.0.0/0" && route.gateway_id == "igw-0123456789abcdef0" && (route.nat_gateway_id == null || route.nat_gateway_id == "")])
    error_message = "Network DNS and a direct Internet Gateway route are required; no NAT route is permitted."
  }
  assert {
    condition     = output.eks_contract.public_endpoint && output.eks_contract.private_endpoint && toset(output.eks_contract.operator_cidrs) == toset(["203.0.113.42/32"]) && !output.eks_contract.creator_admin && output.eks_contract.auth_mode == "API"
    error_message = "EKS must expose a restricted operator endpoint, private node endpoint, and explicit API access."
  }
  assert {
    condition     = output.eks_contract.kubernetes_version == "1.36" && output.eks_contract.support_type == "STANDARD" && output.eks_contract.admin_role == var.admin_role_arn && endswith(output.eks_contract.admin_policy, "/AmazonEKSClusterAdminPolicy")
    error_message = "Explicit admin role, reviewed Kubernetes minor, and standard-support-only upgrades are required."
  }
  assert {
    condition     = output.eks_contract.node_scaling.min_size == 1 && output.eks_contract.node_scaling.desired_size == 1 && output.eks_contract.node_scaling.max_size == 2 && output.eks_contract.credits == "standard" && toset(output.eks_contract.node_instance_type) == toset(["t3.medium"])
    error_message = "The disposable node budget is one desired on-demand t3.medium, bounded to two with standard credits."
  }
  assert {
    condition     = output.eks_contract.metadata.http_tokens == "required" && output.eks_contract.metadata.http_put_response_hop_limit == 1 && alltrue([for mapping in output.eks_contract.root_disk : mapping.ebs[0].encrypted && mapping.ebs[0].volume_type == "gp3" && mapping.ebs[0].volume_size == 20 && mapping.ebs[0].delete_on_termination])
    error_message = "Nodes require IMDSv2 with hop limit one and encrypted, disposable gp3 root disks."
  }
  assert {
    condition     = toset(output.eks_contract.node_policies) == toset(["arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy", "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPullOnly"]) && endswith(output.eks_contract.cni_policy, "/AmazonEKS_CNI_Policy") && jsondecode(output.eks_contract.cni_trust).Statement[0].Action == "sts:AssumeRoleWithWebIdentity" && jsondecode(output.eks_contract.cni_trust).Statement[0].Condition.StringEquals["oidc.eks.us-east-1.amazonaws.com/id/MOCK:sub"] == "system:serviceaccount:kube-system:aws-node" && jsondecode(output.eks_contract.cni_trust).Statement[0].Condition.StringEquals["oidc.eks.us-east-1.amazonaws.com/id/MOCK:aud"] == "sts.amazonaws.com"
    error_message = "CNI permissions belong to its exact service-account IRSA role, not the node role."
  }
  assert {
    condition     = !output.storage_contract.force_destroy && output.storage_contract.versioning == "Enabled" && output.storage_contract.ownership == "BucketOwnerEnforced" && alltrue(values(output.storage_contract.public_access)) && alltrue([for rule in output.storage_contract.encryption : rule.apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"])
    error_message = "Object storage must be private, versioned, owner-enforced, encrypted, and never force-destroyed."
  }
  assert {
    condition     = jsondecode(output.storage_contract.tls_policy).Statement[0].Effect == "Deny" && jsondecode(output.storage_contract.tls_policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false" && jsondecode(output.storage_contract.permissions).Statement[1].Resource == "arn:aws:s3:::skulker-mock-datasets-111122223333/datasets/*" && !strcontains(output.storage_contract.permissions, "s3:Delete")
    error_message = "S3 must deny cleartext transport and scope workload access to dataset objects without deletes."
  }
  assert {
    condition     = jsondecode(output.storage_contract.trust_policy).Statement[0].Principal.Service == "pods.eks.amazonaws.com" && jsondecode(output.storage_contract.trust_policy).Statement[0].Condition.StringEquals["aws:RequestTag/eks-cluster-arn"] == "arn:aws:eks:us-east-1:111122223333:cluster/skulker-research" && jsondecode(output.storage_contract.trust_policy).Statement[0].Condition.StringEquals["aws:RequestTag/kubernetes-namespace"] == "skulker" && jsondecode(output.storage_contract.trust_policy).Statement[0].Condition.StringEquals["aws:RequestTag/kubernetes-service-account"] == "skulker" && output.storage_contract.namespace == "skulker" && output.storage_contract.service_account == "skulker"
    error_message = "Dataset identity must be restricted to this cluster and the intended Kubernetes identity."
  }
}

run "reject_world_open_endpoint" {
  command = plan
  variables { operator_ipv4_cidrs = ["0.0.0.0/0"] }
  expect_failures = [var.operator_ipv4_cidrs]
}
run "reject_overbroad_endpoint" {
  command = plan
  variables { operator_ipv4_cidrs = ["203.0.0.0/16"] }
  expect_failures = [var.operator_ipv4_cidrs]
}
run "reject_missing_operator_endpoint" {
  command = plan
  variables { operator_ipv4_cidrs = [] }
  expect_failures = [var.operator_ipv4_cidrs]
}
run "reject_cross_account_admin" {
  command = plan
  variables { admin_role_arn = "arn:aws:iam::999988887777:role/OtherAccount" }
  expect_failures = [var.admin_role_arn]
}
run "reject_iam_user_admin" {
  command = plan
  variables { admin_role_arn = "arn:aws:iam::111122223333:user/Operator" }
  expect_failures = [var.admin_role_arn]
}
run "reject_single_az" {
  command = plan
  variables { availability_zones = ["us-east-1a", "us-east-1a"] }
  expect_failures = [var.availability_zones]
}
run "reject_unreviewed_kubernetes_minor" {
  command = plan
  variables {
    kubernetes_version       = "1.31"
    node_ami_release_version = "1.31.0-20260101"
  }
  expect_failures = [var.kubernetes_version]
}
run "reject_large_node_budget" {
  command = plan
  variables { node_desired_size = 3 }
  expect_failures = [var.node_desired_size]
}
run "reject_implicit_addon_version" {
  command = plan
  variables { vpc_cni_addon_version = "latest" }
  expect_failures = [var.vpc_cni_addon_version]
}
run "reject_wrong_minor_ami" {
  command = plan
  variables { node_ami_release_version = "1.35.0-20260101" }
  expect_failures = [var.node_ami_release_version]
}
run "reject_bucket_wildcards" {
  command = plan
  variables { bucket_name = "*" }
  expect_failures = [var.bucket_name]
}
run "reject_dataset_prefix_escape" {
  command = plan
  variables { dataset_prefix = "../" }
  expect_failures = [var.dataset_prefix]
}
