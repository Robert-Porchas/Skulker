output "cluster_name" { value = aws_eks_cluster.this.name }
output "cluster_arn" { value = aws_eks_cluster.this.arn }
output "endpoint" { value = aws_eks_cluster.this.endpoint }
output "contract" {
  value = {
    kubernetes_version   = aws_eks_cluster.this.version
    public_endpoint      = aws_eks_cluster.this.vpc_config[0].endpoint_public_access
    private_endpoint     = aws_eks_cluster.this.vpc_config[0].endpoint_private_access
    operator_cidrs       = aws_eks_cluster.this.vpc_config[0].public_access_cidrs
    creator_admin        = aws_eks_cluster.this.access_config[0].bootstrap_cluster_creator_admin_permissions
    auth_mode            = aws_eks_cluster.this.access_config[0].authentication_mode
    support_type         = aws_eks_cluster.this.upgrade_policy[0].support_type
    admin_role           = aws_eks_access_entry.admin.principal_arn
    admin_policy         = aws_eks_access_policy_association.admin.policy_arn
    node_scaling         = aws_eks_node_group.this.scaling_config[0]
    node_instance_type   = aws_eks_node_group.this.instance_types
    node_ami             = aws_eks_node_group.this.release_version
    metadata             = aws_launch_template.node.metadata_options[0]
    root_disk            = aws_launch_template.node.block_device_mappings
    credits              = aws_launch_template.node.credit_specification[0].cpu_credits
    node_policies        = [for attachment in aws_iam_role_policy_attachment.node : attachment.policy_arn]
    cni_trust            = aws_iam_role.cni.assume_role_policy
    cni_policy           = aws_iam_role_policy_attachment.cni.policy_arn
    cni_version          = aws_eks_addon.cni.addon_version
    pod_identity_version = aws_eks_addon.pod_identity.addon_version
    log_retention_days   = aws_cloudwatch_log_group.cluster.retention_in_days
  }
}
