variable "aws_region" {
  type    = string
  default = "us-east-1"
  validation {
    condition     = can(regex("^[a-z]{2}-[a-z]+-[0-9]+$", var.aws_region))
    error_message = "Supply a commercial AWS region name."
  }
}
variable "expected_account_id" {
  description = "Explicit target account guard; the AWS provider rejects credentials for another account."
  type        = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.expected_account_id))
    error_message = "Expected AWS account ID must contain exactly twelve digits."
  }
}
variable "name" {
  type    = string
  default = "skulker-research"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,30}$", var.name))
    error_message = "Use a lowercase 3..31 character resource-name prefix."
  }
}
variable "admin_role_arn" {
  type = string
  validation {
    condition     = can(regex("^arn:aws:iam::${var.expected_account_id}:role/[A-Za-z0-9+=,.@_/-]+$", var.admin_role_arn))
    error_message = "Provide an explicit IAM role ARN in expected_account_id; IAM users and other accounts are rejected."
  }
}
variable "operator_ipv4_cidrs" {
  type = list(string)
  validation {
    condition = length(var.operator_ipv4_cidrs) > 0 && length(var.operator_ipv4_cidrs) <= 4 && alltrue([
      for cidr in var.operator_ipv4_cidrs : can(cidrnetmask(cidr)) && try(tonumber(split("/", cidr)[1]) >= 24 && tonumber(split("/", cidr)[1]) <= 32, false)
    ])
    error_message = "Provide one through four operator IPv4 CIDRs, each /24 or narrower; never 0.0.0.0/0."
  }
}
variable "kubernetes_version" {
  type    = string
  default = "1.36"
  validation {
    condition     = var.kubernetes_version == "1.36"
    error_message = "This reviewed foundation pins EKS 1.36; review compatibility before changing the minor."
  }
}
variable "node_instance_type" {
  type    = string
  default = "t3.medium"
  validation {
    condition     = contains(["t3.medium", "t3.large"], var.node_instance_type)
    error_message = "Use the reviewed x86_64 t3.medium or t3.large instance size."
  }
}
variable "node_desired_size" {
  type    = number
  default = 1
  validation {
    condition     = contains([1, 2], var.node_desired_size)
    error_message = "Desired node count must be one or two."
  }
}
variable "node_root_volume_gib" {
  type    = number
  default = 20
  validation {
    condition     = var.node_root_volume_gib >= 20 && var.node_root_volume_gib <= 100 && floor(var.node_root_volume_gib) == var.node_root_volume_gib
    error_message = "Root disks must be an integral 20 through 100 GiB."
  }
}
variable "node_ami_release_version" {
  description = "Explicit EKS AL2023 x86_64 AMI release, resolved and reviewed before a future cloud plan."
  type        = string
  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+-[0-9]{8}$", var.node_ami_release_version)) && startswith(var.node_ami_release_version, "${var.kubernetes_version}.")
    error_message = "Supply an explicit AMI release matching the pinned Kubernetes minor, such as MINOR.PATCH-YYYYMMDD."
  }
}
variable "vpc_cni_addon_version" {
  type = string
  validation {
    condition     = can(regex("^v[0-9]+\\.[0-9]+\\.[0-9]+-eksbuild\\.[0-9]+$", var.vpc_cni_addon_version))
    error_message = "Supply an explicit compatible VPC CNI eksbuild version; do not select latest implicitly."
  }
}
variable "pod_identity_agent_addon_version" {
  type = string
  validation {
    condition     = can(regex("^v[0-9]+\\.[0-9]+\\.[0-9]+-eksbuild\\.[0-9]+$", var.pod_identity_agent_addon_version))
    error_message = "Supply an explicit compatible Pod Identity Agent eksbuild version."
  }
}
variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
  validation {
    condition     = can(cidrnetmask(var.vpc_cidr)) && try(tonumber(split("/", var.vpc_cidr)[1]) >= 16 && tonumber(split("/", var.vpc_cidr)[1]) <= 20, false)
    error_message = "Use an IPv4 VPC CIDR with prefix length 16 through 20."
  }
}
variable "availability_zones" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b"]
  validation {
    condition     = length(var.availability_zones) == 2 && length(distinct(var.availability_zones)) == 2 && alltrue([for zone in var.availability_zones : startswith(zone, var.aws_region) && can(regex("^[a-z]{2}-[a-z]+-[0-9]+[a-z]$", zone))])
    error_message = "Exactly two distinct availability zones are required."
  }
}
variable "bucket_name" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.bucket_name)) && !can(regex("^[0-9]+-[0-9]+-[0-9]+-[0-9]+$", var.bucket_name))
    error_message = "Use a globally unique 3..63 character lowercase, hyphenated S3 bucket name."
  }
}
variable "dataset_prefix" {
  type    = string
  default = "datasets/"
  validation {
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9/_-]*/$", var.dataset_prefix)) && !strcontains(var.dataset_prefix, "//")
    error_message = "Dataset prefix must be a nonempty relative path ending in / without wildcard or dot segments."
  }
}
variable "workload_namespace" {
  type    = string
  default = "skulker"
  validation {
    condition     = can(regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$", var.workload_namespace)) && length(var.workload_namespace) <= 63
    error_message = "Use a valid Kubernetes namespace."
  }
}
variable "workload_service_account" {
  type    = string
  default = "skulker"
  validation {
    condition     = can(regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$", var.workload_service_account)) && length(var.workload_service_account) <= 63
    error_message = "Use a valid Kubernetes service account name."
  }
}
