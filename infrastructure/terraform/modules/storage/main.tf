resource "aws_s3_bucket" "this" {
  bucket        = var.bucket_name
  force_destroy = false
}
resource "aws_s3_bucket_public_access_block" "this" {
  bucket                  = aws_s3_bucket.this.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id
  rule { object_ownership = "BucketOwnerEnforced" }
}
resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id
  versioning_configuration { status = "Enabled" }
}
resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}
resource "aws_s3_bucket_policy" "tls" {
  bucket = aws_s3_bucket.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.this.arn, "${aws_s3_bucket.this.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.this]
}
resource "aws_iam_role" "datasets" {
  name = "${var.name}-datasets"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
      Condition = { StringEquals = {
        "aws:RequestTag/eks-cluster-arn"            = var.cluster_arn
        "aws:RequestTag/kubernetes-namespace"       = var.namespace
        "aws:RequestTag/kubernetes-service-account" = var.service_account
      } }
    }]
  })
}
resource "aws_iam_role_policy" "datasets" {
  name = "datasets-prefix-only"
  role = aws_iam_role.datasets.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "ListDatasetPrefix"
        Effect    = "Allow"
        Action    = "s3:ListBucket"
        Resource  = aws_s3_bucket.this.arn
        Condition = { StringLike = { "s3:prefix" = [var.dataset_prefix, "${var.dataset_prefix}*"] } }
      },
      {
        Sid      = "ReadWriteDatasetObjects"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
        Resource = "${aws_s3_bucket.this.arn}/${var.dataset_prefix}*"
      }
    ]
  })
}
resource "aws_eks_pod_identity_association" "datasets" {
  cluster_name    = var.cluster_name
  namespace       = var.namespace
  service_account = var.service_account
  role_arn        = aws_iam_role.datasets.arn
}
