data "google_project" "this" {
  project_id = var.project_id
}

locals {
  name   = var.app.name
  prefix = var.app.env_prefix
  cr     = var.app.cloudrun

  wake     = try(local.cr.wake, null)
  has_wake = local.wake != null

  # 바깥 감시 — 칸이 있으면 켠다({} 도 켠 것). 기본 국룰: 5분 간격, 5분에 5건
  monitoring               = try(local.cr.monitoring, null)
  has_monitoring           = local.monitoring != null
  mon_uptime_minutes       = try(local.monitoring.uptime_period_minutes, 5)
  mon_error_threshold      = try(local.monitoring.error_threshold, 5)
  mon_error_window_minutes = try(local.monitoring.error_window_minutes, 5)
  relay_path               = "/_alert-relay"

  # 선언 비밀: operator_only 가 아닌 것은 무작위 판을 만들고, operator_only 는 그릇만 만든다
  declared_secrets = try(tolist(var.app.secrets), [])
  operator_only    = toset(try(var.app.operator_only, []))
  random_declared  = [for s in local.declared_secrets : s if !contains(local.operator_only, s)]

  origin_secret = "ORIGIN_AUTH_SECRET"
  smoke_secrets = ["SMOKE_EMAIL", "SMOKE_PASSWORD"]
  edge_secret   = "${local.prefix}_WEB_CLIENT_IP_EDGE_SECRET_SHA256"
  wake_secret   = "${local.prefix}_JOB_QUEUE_WAKE_SECRET"

  # 서비스에 넣는 플랫폼 비밀(ORIGIN_AUTH_SECRET · SMOKE_* 는 서비스에 넣지 않는다)
  platform_secrets = concat([local.edge_secret], local.has_wake ? [local.wake_secret] : [])

  random_names    = concat(local.random_declared, [local.origin_secret], local.has_wake ? [local.wake_secret] : [])
  container_names = toset(concat(local.declared_secrets, local.platform_secrets, [local.origin_secret], local.smoke_secrets))
  version_names   = toset(concat(local.random_names, [local.edge_secret]))
  run_secrets     = toset(concat(local.declared_secrets, local.platform_secrets))
  deploy_secrets  = toset(concat([local.origin_secret], local.smoke_secrets))

  secret_values = merge(
    { for n in local.random_names : n => random_password.this[n].result },
    { (local.edge_secret) = sha256(random_password.this[local.origin_secret].result) },
  )

  # 결정적 run.app 주소 — 서비스가 생기기 전에도 계산된다
  run_url   = "https://${local.name}-api-${data.google_project.this.number}.${var.region}.run.app"
  drain_url = "${local.run_url}/internal/jobs/drain"

  timeout_seconds = tonumber(trimsuffix(local.cr.timeout, "s"))

  wake_env = {
    "${local.prefix}_JOB_QUEUE_MODE"                       = "on-request"
    "${local.prefix}_JOB_QUEUE_WAKE_CLOUDTASKS_PROJECT"    = var.project_id
    "${local.prefix}_JOB_QUEUE_WAKE_CLOUDTASKS_REGION"     = var.region
    "${local.prefix}_JOB_QUEUE_WAKE_CLOUDTASKS_QUEUE"      = "${local.name}-drain"
    "${local.prefix}_JOB_QUEUE_WAKE_CLOUDTASKS_TARGET_URL" = local.drain_url
  }

  platform_env = merge(
    {
      "${local.prefix}_CLIENT_IP_MODE"     = "edge-secret"
      "${local.prefix}_REDIS_LOCK_ENABLED" = "false"
    },
    { for k, v in local.wake_env : k => v if local.has_wake },
  )
}
