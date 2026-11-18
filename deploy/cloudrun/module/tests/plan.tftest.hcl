# 비밀 · 출력 — 공급자는 모의(실계정 없음).
mock_provider "google" {
  # apply 시험이 쓰는 모의 값 — 서비스 계정 이름은 공급자가 정규식으로 검사한다
  mock_resource "google_service_account" {
    defaults = {
      name  = "projects/acme-proj/serviceAccounts/acme@acme-proj.iam.gserviceaccount.com"
      email = "acme@acme-proj.iam.gserviceaccount.com"
    }
  }
}
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

run "operator_only_secrets_get_no_version" {
  command = plan

  assert {
    condition     = contains(keys(google_secret_manager_secret.this), "SPRING_DATASOURCE_PASSWORD")
    error_message = "operator_only 비밀의 그릇은 만든다"
  }
  assert {
    condition     = !contains(keys(google_secret_manager_secret_version.this), "SPRING_DATASOURCE_PASSWORD")
    error_message = "operator_only 비밀에는 판을 넣지 않는다(사람이 넣는다)"
  }
  assert {
    condition     = !contains(keys(google_secret_manager_secret_version.this), "SMOKE_PASSWORD") && !contains(keys(google_secret_manager_secret_version.this), "SMOKE_EMAIL")
    error_message = "SMOKE_EMAIL · SMOKE_PASSWORD 는 그릇만 만든다"
  }
}

run "random_secrets_get_one_version" {
  command = plan

  assert {
    condition     = contains(keys(google_secret_manager_secret_version.this), "JWT_SECRET")
    error_message = "operator_only 가 아닌 비밀은 무작위 판 1을 만든다"
  }
  assert {
    condition     = random_password.this["JWT_SECRET"].length == 48
    error_message = "무작위 값은 48자"
  }
}

run "edge_secret_sha_is_sha256_of_origin_secret" {
  command = apply

  assert {
    condition = (
      google_secret_manager_secret_version.this["ACME_WEB_CLIENT_IP_EDGE_SECRET_SHA256"].secret_data
      == sha256(random_password.this["ORIGIN_AUTH_SECRET"].result)
    )
    error_message = "edge 비밀의 값은 ORIGIN_AUTH_SECRET 의 sha256"
  }
  assert {
    condition     = contains(output.platform_secrets, "ACME_WEB_CLIENT_IP_EDGE_SECRET_SHA256")
    error_message = "서비스에 넣는 플랫폼 비밀에 edge sha 이름이 있다"
  }
  assert {
    condition     = !contains(output.platform_secrets, "ORIGIN_AUTH_SECRET")
    error_message = "ORIGIN_AUTH_SECRET 은 서비스에 넣지 않는다"
  }
}

run "github_variables_has_all_ten_keys" {
  command = plan

  assert {
    condition = toset(keys(output.github_variables)) == toset([
      "GCP_PROJECT_ID", "GCP_PROJECT_NUMBER", "GCP_REGION", "GCP_WIF_PROVIDER", "GCP_DEPLOY_SA",
      "GCP_RUNTIME_SA", "CLOUDRUN_SERVICE", "CLOUDRUN_IMAGE_REPO", "CLOUDRUN_PLATFORM_ENV", "CLOUDRUN_PLATFORM_SECRETS",
    ])
    error_message = "github_variables 는 열 칸"
  }
  assert {
    condition     = output.github_variables["GCP_PROJECT_NUMBER"] == "123456789012" && output.github_variables["CLOUDRUN_SERVICE"] == "acme-api"
    error_message = "프로젝트 번호 · 서비스 이름"
  }
  assert {
    condition     = output.image_repository == "asia-northeast3-docker.pkg.dev/acme-proj/acme/acme"
    error_message = "이미지 저장소 경로"
  }
}
