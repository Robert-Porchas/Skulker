variable "name" { type = string }
variable "cluster_name" { type = string }
variable "cluster_arn" { type = string }
variable "bucket_name" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.bucket_name)) && !can(regex("^[0-9]+-[0-9]+-[0-9]+-[0-9]+$", var.bucket_name))
    error_message = "Use a globally unique 3..63 character lowercase, hyphenated S3 bucket name."
  }
}
variable "namespace" {
  type    = string
  default = "skulker"
}
variable "service_account" {
  type    = string
  default = "skulker"
}
variable "dataset_prefix" {
  type    = string
  default = "datasets/"
  validation {
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9/_-]*/$", var.dataset_prefix)) && !strcontains(var.dataset_prefix, "//")
    error_message = "Dataset prefix must be a nonempty relative path ending in / without wildcard or dot segments."
  }
}
