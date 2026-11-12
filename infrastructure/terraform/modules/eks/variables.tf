variable "name" { type = string }
variable "subnet_ids" {
  type = list(string)
  validation {
    condition     = length(var.subnet_ids) == 2
    error_message = "EKS requires exactly two subnets in this disposable foundation."
  }
}
variable "admin_role_arn" { type = string }
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
