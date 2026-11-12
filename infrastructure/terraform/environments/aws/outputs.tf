output "cluster_name" { value = module.eks.cluster_name }
output "cluster_endpoint" { value = module.eks.endpoint }
output "dataset_bucket_name" { value = module.storage.bucket_name }
output "dataset_workload_role_arn" { value = module.storage.workload_role_arn }
output "network_contract" { value = module.network.contract }
output "eks_contract" { value = module.eks.contract }
output "storage_contract" { value = module.storage.contract }
