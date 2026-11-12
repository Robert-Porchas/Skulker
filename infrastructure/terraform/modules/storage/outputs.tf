output "bucket_name" { value = aws_s3_bucket.this.bucket }
output "bucket_arn" { value = aws_s3_bucket.this.arn }
output "workload_role_arn" { value = aws_iam_role.datasets.arn }
output "contract" {
  value = {
    force_destroy = aws_s3_bucket.this.force_destroy
    public_access = {
      block_acls   = aws_s3_bucket_public_access_block.this.block_public_acls
      block_policy = aws_s3_bucket_public_access_block.this.block_public_policy
      ignore_acls  = aws_s3_bucket_public_access_block.this.ignore_public_acls
      restrict     = aws_s3_bucket_public_access_block.this.restrict_public_buckets
    }
    ownership       = aws_s3_bucket_ownership_controls.this.rule[0].object_ownership
    versioning      = aws_s3_bucket_versioning.this.versioning_configuration[0].status
    encryption      = aws_s3_bucket_server_side_encryption_configuration.this.rule
    tls_policy      = aws_s3_bucket_policy.tls.policy
    trust_policy    = aws_iam_role.datasets.assume_role_policy
    permissions     = aws_iam_role_policy.datasets.policy
    namespace       = aws_eks_pod_identity_association.datasets.namespace
    service_account = aws_eks_pod_identity_association.datasets.service_account
  }
}
