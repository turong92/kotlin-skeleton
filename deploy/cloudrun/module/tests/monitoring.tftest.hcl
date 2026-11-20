# 바깥 감시 — 업타임 체크 · 경보 정책 · Discord 중계 Worker (선언에 cloudrun.monitoring 이 있을 때만).
mock_provider "google" {}
mock_provider "random" {}
mock_provider "cloudflare" {}

override_data {
  target = data.google_project.this
  values = { number = "123456789012" }
}

variables {
  project_id            = "acme-proj"
  region                = "asia-northeast3"
  github_repository     = "acme-org/acme-api"
  cloudflare_account_id = "0123456789abcdef0123456789abcdef"
  cloudflare_zone_id    = "fedcba9876543210fedcba9876543210"
  app                   = yamldecode(file("tests/fixtures/app-monitoring.yaml"))
  discord_webhook_url   = "https://discord.example.com/api/webhooks/1/abc"
}

run "nothing_without_monitoring" {
  command = plan
  variables {
    app = yamldecode(file("tests/fixtures/app.yaml"))
  }

  assert {
    condition = (length(google_monitoring_uptime_check_config.api) + length(google_monitoring_alert_policy.uptime) +
      length(google_monitoring_alert_policy.errors) + length(google_monitoring_notification_channel.discord) +
    length(cloudflare_workers_script.alert_relay)) == 0
    error_message = "monitoring 이 없으면 감시 자원을 하나도 만들지 않는다"
  }
  assert {
    condition     = !contains(local.apis, "monitoring.googleapis.com")
    error_message = "monitoring 이 없으면 monitoring API 도 켜지 않는다"
  }
}

run "creates_one_uptime_two_policies_one_channel_one_worker" {
  command = plan

  assert {
    condition     = length(google_monitoring_uptime_check_config.api) == 1 && length(google_monitoring_alert_policy.uptime) == 1 && length(google_monitoring_alert_policy.errors) == 1
    error_message = "업타임 1 · 정책 2"
  }
  assert {
    condition     = length(google_monitoring_notification_channel.discord) == 1 && length(cloudflare_workers_script.alert_relay) == 1 && length(cloudflare_workers_route.alert_relay) == 1
    error_message = "채널 1 · Worker 1 · 경로 1"
  }
  assert {
    condition     = contains(local.apis, "monitoring.googleapis.com")
    error_message = "monitoring API"
  }
}

run "uptime_hits_the_public_health_path_every_five_minutes" {
  command = plan

  assert {
    condition     = google_monitoring_uptime_check_config.api[0].http_check[0].path == "/api/v1/health" && google_monitoring_uptime_check_config.api[0].http_check[0].use_ssl
    error_message = "공개 도메인의 <api_prefix>/health 를 https 로"
  }
  assert {
    condition     = google_monitoring_uptime_check_config.api[0].monitored_resource[0].labels["host"] == "acme.example.com" && google_monitoring_uptime_check_config.api[0].period == "300s"
    error_message = "호스트는 공개 도메인, 기본 간격 5분"
  }
}

run "error_policy_counts_cloud_run_5xx_with_defaults" {
  command = plan

  assert {
    condition     = strcontains(google_monitoring_alert_policy.errors[0].conditions[0].condition_threshold[0].filter, "run.googleapis.com/request_count") && strcontains(google_monitoring_alert_policy.errors[0].conditions[0].condition_threshold[0].filter, "5xx")
    error_message = "Cloud Run request_count 의 5xx"
  }
  assert {
    condition     = google_monitoring_alert_policy.errors[0].conditions[0].condition_threshold[0].threshold_value == 5 && google_monitoring_alert_policy.errors[0].conditions[0].condition_threshold[0].aggregations[0].alignment_period == "300s"
    error_message = "기본: 5분에 5건"
  }
}

run "thresholds_come_from_the_declaration" {
  command = plan
  variables {
    app = merge(yamldecode(file("tests/fixtures/app-monitoring.yaml")), {
      cloudrun = merge(yamldecode(file("tests/fixtures/app-monitoring.yaml")).cloudrun, {
        monitoring = { uptime_period_minutes = 1, error_threshold = 20, error_window_minutes = 10 }
      })
    })
  }

  assert {
    condition     = google_monitoring_uptime_check_config.api[0].period == "60s" && google_monitoring_alert_policy.errors[0].conditions[0].condition_threshold[0].threshold_value == 20 && google_monitoring_alert_policy.errors[0].conditions[0].condition_threshold[0].aggregations[0].alignment_period == "600s"
    error_message = "선언의 값이 쓰인다"
  }
}

run "rejects_bad_monitoring_values" {
  command = plan
  variables {
    app = merge(yamldecode(file("tests/fixtures/app-monitoring.yaml")), {
      cloudrun = merge(yamldecode(file("tests/fixtures/app-monitoring.yaml")).cloudrun, {
        monitoring = { uptime_period_minutes = 3 }
      })
    })
  }
  expect_failures = [var.app]
}

run "rejects_zero_error_threshold" {
  command = plan
  variables {
    app = merge(yamldecode(file("tests/fixtures/app-monitoring.yaml")), {
      cloudrun = merge(yamldecode(file("tests/fixtures/app-monitoring.yaml")).cloudrun, {
        monitoring = { error_threshold = 0 }
      })
    })
  }
  expect_failures = [var.app]
}

run "rejects_monitoring_without_discord_webhook" {
  command = plan
  variables {
    discord_webhook_url = ""
  }
  expect_failures = [cloudflare_workers_script.alert_relay]
}
