output "run_url" {
  value = module.app.run_url
}

output "service_name" {
  value = module.app.service_name
}

output "image_repository" {
  value = module.app.image_repository
}

output "runtime_service_account" {
  value = module.app.runtime_service_account
}

output "deploy_service_account" {
  value = module.app.deploy_service_account
}

output "workload_identity_provider" {
  value = module.app.workload_identity_provider
}

output "pages_project" {
  value = module.app.pages_project
}

output "r2_bucket" {
  value = module.app.r2_bucket
}

output "tasks_queue" {
  value = module.app.tasks_queue
}

output "platform_env" {
  value = module.app.platform_env
}

output "platform_secrets" {
  value = module.app.platform_secrets
}

output "github_variables" {
  description = "앱 kotlin 레포의 GitHub 레포 변수"
  value       = module.app.github_variables
}

output "web_github_variables" {
  description = "react 레포의 GitHub 레포 변수"
  value       = module.app.web_github_variables
}
