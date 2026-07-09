# Sample Terraform (HCL) — highlighting (source.hcl.terraform) + the terraform-ls LSP.
terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

resource "aws_s3_bucket" "example" {
  bucket = "editora-sample-${var.region}"

  tags = {
    Name        = "Editora Sample"
    Environment = "dev"
  }
}

output "bucket_name" {
  value = aws_s3_bucket.example.bucket
}
