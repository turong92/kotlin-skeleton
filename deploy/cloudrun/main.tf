# 루트 — 선언(deploy/app.yaml)을 읽어 module/ 에 넘긴다. 실행은 맥에서 `cloud prod terraform …` (README.md).
terraform {
  required_version = ">= 1.7"

  # 부분 설정 — 버킷(`<project_id>-tfstate`)은 래퍼(cloud prod terraform)가 init 때 넣는다
  backend "gcs" {}

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }
}

locals {
  region = "asia-northeast3"
}

provider "google" {
  project = var.project_id
  region  = local.region
}

# 토큰은 환경변수 CLOUDFLARE_API_TOKEN
provider "cloudflare" {}

module "app" {
  source = "./module"

  app                   = yamldecode(file("${path.module}/../app.yaml"))
  project_id            = var.project_id
  region                = local.region
  github_repository     = var.github_repository
  cloudflare_account_id = var.cloudflare_account_id
  cloudflare_zone_id    = var.cloudflare_zone_id
}
