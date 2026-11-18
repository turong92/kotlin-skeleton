output "run_url" {
  value = local.run_url
}

output "service_name" {
  value = "${local.name}-api"
}

output "image_repository" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${local.name}/${local.name}"
}

output "runtime_service_account" {
  value = google_service_account.run.email
}

output "deploy_service_account" {
  value = google_service_account.deploy.email
}

output "workload_identity_provider" {
  value = google_iam_workload_identity_pool_provider.github.name
}

output "pages_project" {
  value = cloudflare_pages_project.web.name
}

output "r2_bucket" {
  value = cloudflare_r2_bucket.files.name
}

output "tasks_queue" {
  value = local.has_wake ? google_cloud_tasks_queue.drain[0].name : null
}

output "platform_env" {
  value = local.platform_env
}

output "platform_secrets" {
  description = "서비스에 넣는 플랫폼 비밀 이름"
  value       = local.platform_secrets
}

# 앱 kotlin 레포의 GitHub 레포 변수 — 비밀 아님
output "github_variables" {
  value = {
    GCP_PROJECT_ID            = var.project_id
    GCP_PROJECT_NUMBER        = data.google_project.this.number
    GCP_REGION                = var.region
    GCP_WIF_PROVIDER          = google_iam_workload_identity_pool_provider.github.name
    GCP_DEPLOY_SA             = google_service_account.deploy.email
    GCP_RUNTIME_SA            = google_service_account.run.email
    CLOUDRUN_SERVICE          = "${local.name}-api"
    CLOUDRUN_IMAGE_REPO       = "${var.region}-docker.pkg.dev/${var.project_id}/${local.name}/${local.name}"
    CLOUDRUN_PLATFORM_ENV     = jsonencode(local.platform_env)
    CLOUDRUN_PLATFORM_SECRETS = jsonencode(local.platform_secrets)
  }
}

# react 레포의 GitHub 레포 변수
output "web_github_variables" {
  value = {
    PAGES_PROJECT = cloudflare_pages_project.web.name
  }
}
