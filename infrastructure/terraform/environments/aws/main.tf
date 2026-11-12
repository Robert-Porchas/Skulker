provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.expected_account_id]
  default_tags {
    tags = { Project = "Skulker", Environment = "disposable-research", ManagedBy = "Terraform" }
  }
}

module "network" {
  source             = "../../modules/network"
  name               = var.name
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones
}

module "eks" {
  source                           = "../../modules/eks"
  name                             = var.name
  subnet_ids                       = module.network.subnet_ids
  admin_role_arn                   = var.admin_role_arn
  operator_ipv4_cidrs              = var.operator_ipv4_cidrs
  kubernetes_version               = var.kubernetes_version
  node_instance_type               = var.node_instance_type
  node_desired_size                = var.node_desired_size
  node_root_volume_gib             = var.node_root_volume_gib
  node_ami_release_version         = var.node_ami_release_version
  vpc_cni_addon_version            = var.vpc_cni_addon_version
  pod_identity_agent_addon_version = var.pod_identity_agent_addon_version
}

module "storage" {
  source          = "../../modules/storage"
  name            = var.name
  cluster_name    = module.eks.cluster_name
  cluster_arn     = module.eks.cluster_arn
  bucket_name     = var.bucket_name
  namespace       = var.workload_namespace
  service_account = var.workload_service_account
  dataset_prefix  = var.dataset_prefix
  depends_on      = [module.eks]
}
