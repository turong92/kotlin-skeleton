# 깨우기 — Cloud Tasks 큐 · Scheduler (선언에 cloudrun.wake 가 있을 때만).
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
  app                   = yamldecode(file("tests/fixtures/app.yaml"))
}

run "no_queue_and_no_scheduler_without_wake" {
  command = plan
  variables {
    app = yamldecode(file("tests/fixtures/app-nowake.yaml"))
  }

  assert {
    condition     = length(google_cloud_tasks_queue.drain) == 0 && length(google_cloud_scheduler_job.tick) == 0 && length(google_cloud_tasks_queue_iam_member.enqueuer) == 0
    error_message = "wake 가 없으면 큐 · Scheduler · enqueuer 를 만들지 않는다"
  }
  assert {
    condition     = output.tasks_queue == null
    error_message = "tasks_queue 는 null"
  }
  assert {
    condition     = !contains(keys(output.platform_env), "ACME_JOB_QUEUE_MODE") && !contains(output.platform_secrets, "ACME_JOB_QUEUE_WAKE_SECRET")
    error_message = "wake 가 없으면 깨우기 환경 · 비밀을 넣지 않는다"
  }
}

run "queue_enqueuer_is_bound_on_the_queue_only" {
  command = plan

  assert {
    condition     = google_cloud_tasks_queue_iam_member.enqueuer[0].role == "roles/cloudtasks.enqueuer"
    error_message = "실행 계정에 큐의 enqueuer"
  }
  assert {
    condition     = google_cloud_tasks_queue_iam_member.enqueuer[0].name == google_cloud_tasks_queue.drain[0].name
    error_message = "큐 단위 바인딩"
  }
  assert {
    condition     = google_cloud_tasks_queue.drain[0].name == "acme-drain"
    error_message = "큐 이름은 N-drain"
  }
  assert {
    condition     = google_project_iam_member.deploy_run_admin.role == "roles/run.admin"
    error_message = "프로젝트 단위로는 배포 계정의 run.admin 뿐(enqueuer 는 프로젝트에 주지 않는다)"
  }
}

run "scheduler_targets_deterministic_run_url_with_wake_header" {
  command = plan

  assert {
    condition     = google_cloud_scheduler_job.tick[0].http_target[0].uri == "https://acme-api-123456789012.asia-northeast3.run.app/internal/jobs/drain"
    error_message = "대상은 결정적 run.app 주소의 drain 경로"
  }
  assert {
    condition     = contains(keys(google_cloud_scheduler_job.tick[0].http_target[0].headers), "X-Wake-Secret")
    error_message = "X-Wake-Secret 헤더"
  }
  assert {
    condition     = google_cloud_scheduler_job.tick[0].time_zone == "Asia/Seoul" && google_cloud_scheduler_job.tick[0].attempt_deadline == "300s"
    error_message = "시간대 Asia/Seoul, attempt_deadline = cloudrun.timeout"
  }
  assert {
    condition     = output.platform_env["ACME_JOB_QUEUE_WAKE_CLOUDTASKS_TARGET_URL"] == "https://acme-api-123456789012.asia-northeast3.run.app/internal/jobs/drain"
    error_message = "플랫폼이 넣는 target-url 도 같은 주소"
  }
}
