# 선언 검증 — plan 이 고칠 칸 이름을 말하며 거부한다 (모두 var.app 의 validation).
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

run "accepts_the_reference_declaration" {
  command = plan
}

run "rejects_redis_true" {
  command = plan
  variables {
    app = yamldecode(file("tests/fixtures/app-redis.yaml"))
  }
  expect_failures = [var.app]
}

run "rejects_db_postgres" {
  command = plan
  variables {
    app = yamldecode(file("tests/fixtures/app-postgres.yaml"))
  }
  expect_failures = [var.app]
}

run "rejects_reserved_env_name" {
  command = plan
  variables {
    app = yamldecode(file("tests/fixtures/app-reserved.yaml"))
  }
  expect_failures = [var.app]
}

run "rejects_external_db_without_operator_datasource_secrets" {
  command = plan
  variables {
    app = merge(yamldecode(file("tests/fixtures/app.yaml")), {
      operator_only = ["SPRING_DATASOURCE_URL"]
    })
  }
  expect_failures = [var.app]
}

run "rejects_missing_cloudrun_memory" {
  command = plan
  variables {
    app = merge(yamldecode(file("tests/fixtures/app.yaml")), {
      cloudrun = {
        for k, v in yamldecode(file("tests/fixtures/app.yaml")).cloudrun : k => v if k != "memory"
      }
    })
  }
  expect_failures = [var.app]
}
