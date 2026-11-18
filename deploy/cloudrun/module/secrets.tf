# 비밀 그릇은 전부 만들고, 판은 무작위인 것과 edge sha 에만 넣는다.
# operator_only · SMOKE_* 의 값은 사람이 넣는다(`cloud prod gcloud secrets versions add …`).
resource "google_secret_manager_secret" "this" {
  for_each  = local.container_names
  project   = var.project_id
  secret_id = each.key

  replication {
    auto {}
  }

  depends_on = [google_project_service.this]
}

resource "random_password" "this" {
  for_each = toset(local.random_names)
  length   = 48
  special  = false
}

resource "google_secret_manager_secret_version" "this" {
  for_each    = local.version_names
  secret      = google_secret_manager_secret.this[each.key].id
  secret_data = local.secret_values[each.key]
}
