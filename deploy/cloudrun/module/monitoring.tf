# 바깥 감시 — 선언에 cloudrun.monitoring 이 있을 때만. Cloud Run 앞단이 앱에 닿기 전에 내는 503 은 앱 안의 alert 모듈이 못 본다.
# 업타임 체크(공개 도메인의 <api_prefix>/health, worker.js 가 원점 /health 로 넘긴다) + 경보 정책 둘 → webhook_basicauth 채널 → 중계 Worker(alert-relay.js) → Discord.

resource "random_password" "relay_token" {
  count   = local.has_monitoring ? 1 : 0
  length  = 32
  special = false # Basic 인증의 비밀번호로 간다
}

resource "google_monitoring_uptime_check_config" "api" {
  count        = local.has_monitoring ? 1 : 0
  project      = var.project_id
  display_name = "${local.name} health"
  timeout      = "10s"
  period       = "${local.mon_uptime_minutes * 60}s"

  http_check {
    path           = "${var.app.api_prefix}/health"
    port           = 443
    use_ssl        = true
    validate_ssl   = true
    request_method = "GET"
  }

  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = var.project_id
      host       = var.app.domain
    }
  }

  depends_on = [google_project_service.this]
}

resource "google_monitoring_notification_channel" "discord" {
  count        = local.has_monitoring ? 1 : 0
  project      = var.project_id
  display_name = "${local.name} discord relay"
  type         = "webhook_basicauth" # webhook_tokenauth 는 url 라벨뿐이라 토큰을 실을 곳이 없다

  labels = {
    url      = "https://${var.app.domain}${local.relay_path}"
    username = "relay" # alert-relay.js 의 USERNAME 과 같다
  }
  sensitive_labels {
    password = random_password.relay_token[0].result
  }

  depends_on = [google_project_service.this]
}

# (a) 업타임 실패 — 검사 지점 둘 이상이 동시에 실패할 때(한 지점의 일시 장애로는 울리지 않는다)
resource "google_monitoring_alert_policy" "uptime" {
  count                 = local.has_monitoring ? 1 : 0
  project               = var.project_id
  display_name          = "${local.name} uptime check failing"
  combiner              = "OR"
  notification_channels = [google_monitoring_notification_channel.discord[0].id]

  # 점이 안 들어오면(오류가 멈추면) 기본 7일까지 열려 있어 다음 장애를 놓친다 — 30분 뒤 닫는다
  alert_strategy {
    auto_close = "1800s"
  }

  conditions {
    display_name = "health check failed"
    condition_threshold {
      filter          = "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND metric.label.check_id=\"${google_monitoring_uptime_check_config.api[0].uptime_check_id}\" AND resource.type=\"uptime_url\""
      comparison      = "COMPARISON_GT"
      threshold_value = 1
      duration        = "60s"

      aggregations {
        alignment_period     = "${max(1200, local.mon_uptime_minutes * 60 * 2)}s" # 검사 간격보다 길게 묶는다
        per_series_aligner   = "ALIGN_NEXT_OLDER"
        cross_series_reducer = "REDUCE_COUNT_FALSE"
        group_by_fields      = ["resource.label.host"]
      }
      trigger {
        count = 1
      }
    }
  }
}

# (b) Cloud Run 요청 5xx — 창 안에 N건 이상 (앞단이 내는 503 도 이 지표에 잡힌다)
resource "google_monitoring_alert_policy" "errors" {
  count                 = local.has_monitoring ? 1 : 0
  project               = var.project_id
  display_name          = "${local.name} cloud run 5xx"
  combiner              = "OR"
  notification_channels = [google_monitoring_notification_channel.discord[0].id]

  alert_strategy {
    auto_close = "1800s"
  }

  conditions {
    display_name = "5xx responses"
    condition_threshold {
      filter          = "metric.type=\"run.googleapis.com/request_count\" AND resource.type=\"cloud_run_revision\" AND resource.label.service_name=\"${local.name}-api\" AND metric.label.response_code_class=\"5xx\""
      comparison      = "COMPARISON_GE"
      threshold_value = local.mon_error_threshold
      duration        = "0s"

      aggregations {
        alignment_period     = "${local.mon_error_window_minutes * 60}s"
        per_series_aligner   = "ALIGN_SUM"
        cross_series_reducer = "REDUCE_SUM"
      }
      trigger {
        count = 1
      }
    }
  }
}

# Monitoring 웹훅 본문은 Discord 형식이 아니다 — 작은 Worker 가 한 줄로 바꿔 보낸다 (alert-relay.js)
resource "cloudflare_workers_script" "alert_relay" {
  count              = local.has_monitoring ? 1 : 0
  account_id         = var.cloudflare_account_id
  script_name        = "${local.name}-alert-relay"
  content            = file("${path.module}/../alert-relay.js")
  main_module        = "alert-relay.js"
  compatibility_date = "2025-01-01"

  bindings = [
    { name = "RELAY_TOKEN", type = "secret_text", text = random_password.relay_token[0].result },
    { name = "DISCORD_WEBHOOK_URL", type = "secret_text", text = var.discord_webhook_url },
  ]

  lifecycle {
    precondition {
      condition     = var.discord_webhook_url != ""
      error_message = "cloudrun.monitoring 이 있으면 discord_webhook_url(terraform.tfvars, 값은 사람이 넣는다)이 있어야 한다."
    }
  }
}

resource "cloudflare_workers_route" "alert_relay" {
  count   = local.has_monitoring ? 1 : 0
  zone_id = var.cloudflare_zone_id
  pattern = "${var.app.domain}${local.relay_path}"
  script  = cloudflare_workers_script.alert_relay[0].script_name
}
