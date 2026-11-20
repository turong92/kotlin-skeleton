variable "app" {
  description = "앱 선언 한 장(deploy/app.yaml 을 yamldecode 한 것). 읽는 칸은 deploy/cloudrun/README.md"
  type        = any

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,19}$", try(var.app.name, "")))
    error_message = "name 은 소문자로 시작하고 소문자·숫자·하이픈만, 2~20자여야 한다 (자원 이름의 어근)."
  }

  validation {
    condition     = can(regex("^[A-Z][A-Z0-9_]*$", try(var.app.env_prefix, "")))
    error_message = "env_prefix 는 대문자·숫자·밑줄만 쓰고 대문자로 시작해야 한다 (예: ACME)."
  }

  validation {
    condition     = try(var.app.domain, "") != ""
    error_message = "domain 이 있어야 한다 (Pages 와 Worker 경로의 호스트)."
  }

  validation {
    condition     = try(startswith(var.app.api_prefix, "/") && !endswith(var.app.api_prefix, "/") && length(var.app.api_prefix) > 1, false)
    error_message = "api_prefix 는 / 로 시작하고 / 로 끝나지 않는 경로여야 한다 (예: /api/v1)."
  }

  validation {
    condition     = contains(["external", "none"], try(var.app.db, "none"))
    error_message = "db 는 external 또는 none 이어야 한다 (postgres · mysql 은 홈서버 전용)."
  }

  validation {
    condition = try(var.app.db, "none") != "external" || alltrue([
      for n in ["SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"] :
      contains(try(toset(var.app.operator_only), toset([])), n)
    ])
    error_message = "db: external 이면 SPRING_DATASOURCE_URL · SPRING_DATASOURCE_USERNAME · SPRING_DATASOURCE_PASSWORD 가 operator_only 에 있어야 한다."
  }

  validation {
    condition     = try(var.app.redis, false) == false
    error_message = "redis 는 false 여야 한다 (Cloud Run 틀은 Redis 를 만들지 않는다)."
  }

  validation {
    condition     = length(setsubtract(try(toset(var.app.operator_only), toset([])), try(toset(var.app.secrets), toset([])))) == 0
    error_message = "operator_only 의 이름은 모두 secrets 에도 있어야 한다."
  }

  validation {
    condition = alltrue([
      for k in ["dockerfile", "cpu", "memory", "min_instances", "max_instances", "concurrency", "timeout"] :
      try(var.app.cloudrun[k], null) != null
    ])
    error_message = "cloudrun.dockerfile · cpu · memory · min_instances · max_instances · concurrency · timeout 이 모두 있어야 한다."
  }

  validation {
    condition     = try(var.app.cloudrun.min_instances >= 0 && var.app.cloudrun.min_instances <= var.app.cloudrun.max_instances, false)
    error_message = "cloudrun.min_instances 와 max_instances 는 0 ≤ min ≤ max 여야 한다."
  }

  validation {
    condition     = try(can(regex("^[0-9]+s$", var.app.cloudrun.timeout)), false)
    error_message = "cloudrun.timeout 은 초 단위 문자열이어야 한다 (예: 300s)."
  }

  validation {
    condition     = try(var.app.cloudrun.wake, null) == null || try(trimspace(var.app.cloudrun.wake.schedule) != "", false)
    error_message = "cloudrun.wake 가 있으면 cloudrun.wake.schedule 이 빈 값이 아니어야 한다 (예: \"*/5 * * * *\")."
  }

  validation {
    condition = try(var.app.cloudrun.monitoring, null) == null || (
      contains([1, 5, 10, 15], try(var.app.cloudrun.monitoring.uptime_period_minutes, 5)) &&
      try(var.app.cloudrun.monitoring.error_threshold, 5) >= 1 &&
      try(var.app.cloudrun.monitoring.error_window_minutes, 5) >= 1
    )
    error_message = "cloudrun.monitoring 의 uptime_period_minutes 는 1 · 5 · 10 · 15 중 하나, error_threshold · error_window_minutes 는 1 이상이어야 한다."
  }

  validation {
    condition = length(setintersection(
      toset(concat(try(keys(var.app.env), []), try(tolist(var.app.secrets), []))),
      toset(concat(
        ["ORIGIN_AUTH_SECRET", "SMOKE_EMAIL", "SMOKE_PASSWORD"],
        [for s in [
          "CLIENT_IP_MODE", "REDIS_LOCK_ENABLED", "WEB_CLIENT_IP_EDGE_SECRET_SHA256",
          "JOB_QUEUE_MODE", "JOB_QUEUE_WAKE_SECRET", "JOB_QUEUE_WAKE_CLOUDTASKS_PROJECT",
          "JOB_QUEUE_WAKE_CLOUDTASKS_REGION", "JOB_QUEUE_WAKE_CLOUDTASKS_QUEUE", "JOB_QUEUE_WAKE_CLOUDTASKS_TARGET_URL",
        ] : "${try(var.app.env_prefix, "X")}_${s}"]
      ))
    )) == 0
    error_message = "env · secrets 에 플랫폼이 넣는 예약 이름(<env_prefix>_CLIENT_IP_MODE · _REDIS_LOCK_ENABLED · _WEB_CLIENT_IP_EDGE_SECRET_SHA256 · _JOB_QUEUE_* · ORIGIN_AUTH_SECRET · SMOKE_EMAIL · SMOKE_PASSWORD)을 쓸 수 없다."
  }
}

variable "project_id" {
  description = "앱 하나 = GCP 프로젝트 하나"
  type        = string
}

variable "region" {
  description = "Cloud Run · Tasks · Scheduler · Artifact Registry 의 리전"
  type        = string

  validation {
    condition     = var.region == "asia-northeast3"
    error_message = "region 은 asia-northeast3 하나다."
  }
}

variable "github_repository" {
  description = "WIF 가 신뢰하는 앱 레포 (owner/repo)"
  type        = string
}

variable "cloudflare_account_id" {
  type = string
}

variable "cloudflare_zone_id" {
  type = string
}

variable "discord_webhook_url" {
  description = "경보를 받을 Discord 웹훅 주소 — cloudrun.monitoring 이 있을 때만 쓴다(값은 사람이 tfvars 에 넣는다)"
  type        = string
  sensitive   = true
  default     = ""
}
