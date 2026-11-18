# 깨우기 — 선언에 cloudrun.wake 가 있을 때만. Tasks 큐는 앱이 일을 넣을 때, Scheduler 는 주기 일과 그물.
resource "google_cloud_tasks_queue" "drain" {
  count    = local.has_wake ? 1 : 0
  project  = var.project_id
  name     = "${local.name}-drain"
  location = var.region

  rate_limits {
    max_concurrent_dispatches = 1
  }

  retry_config {
    max_attempts = 3
    min_backoff  = "1s"
    max_backoff  = "10s"
  }

  depends_on = [google_project_service.this]
}

# 큐 하나에만 작업을 넣는다 — 프로젝트 단위로 주지 않는다
resource "google_cloud_tasks_queue_iam_member" "enqueuer" {
  count    = local.has_wake ? 1 : 0
  project  = var.project_id
  location = var.region
  name     = google_cloud_tasks_queue.drain[0].name
  role     = "roles/cloudtasks.enqueuer"
  member   = "serviceAccount:${google_service_account.run.email}"
}

# 서비스가 allUsers 호출을 허용하므로 OIDC 는 붙이지 않고 X-Wake-Secret 으로 인증한다
resource "google_cloud_scheduler_job" "tick" {
  count            = local.has_wake ? 1 : 0
  project          = var.project_id
  region           = var.region
  name             = "${local.name}-tick"
  schedule         = local.wake.schedule
  time_zone        = "Asia/Seoul"
  attempt_deadline = "${min(local.timeout_seconds, 1800)}s"

  http_target {
    http_method = "POST"
    uri         = local.drain_url
    headers = {
      "X-Wake-Secret" = random_password.this[local.wake_secret].result
    }
  }

  depends_on = [google_project_service.this]
}
