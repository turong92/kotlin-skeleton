variable "project_id" {
  description = "GCP 프로젝트 ID (래퍼가 TF_VAR_project_id 로 넣는다)"
  type        = string
}

variable "cloudflare_account_id" {
  description = "Cloudflare 계정 ID (래퍼가 TF_VAR_cloudflare_account_id 로 넣는다)"
  type        = string
}

variable "github_repository" {
  description = "WIF 가 신뢰하는 앱 레포 (owner/repo) — terraform.tfvars"
  type        = string
}

variable "discord_webhook_url" {
  description = "경보를 받을 Discord 웹훅 주소 — cloudrun.monitoring 이 있을 때만 필요. terraform.tfvars(git 밖)"
  type        = string
  sensitive   = true
  default     = ""
}

variable "cloudflare_zone_id" {
  description = "도메인이 든 Cloudflare 영역 ID — terraform.tfvars"
  type        = string
}
