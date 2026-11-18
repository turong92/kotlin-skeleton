# Worker — `<domain><api_prefix>/*` 만 Cloud Run 으로 넘긴다 (worker.js)
resource "cloudflare_workers_script" "api_proxy" {
  account_id         = var.cloudflare_account_id
  script_name        = "${local.name}-api-proxy"
  content            = file("${path.module}/../worker.js")
  main_module        = "worker.js"
  compatibility_date = "2025-01-01"

  bindings = [
    { name = "ORIGIN_URL", type = "plain_text", text = local.run_url },
    { name = "API_PREFIX", type = "plain_text", text = "${var.app.api_prefix}/" },
    { name = "ORIGIN_AUTH_SECRET", type = "secret_text", text = random_password.this[local.origin_secret].result },
  ]
}

resource "cloudflare_workers_route" "api" {
  zone_id = var.cloudflare_zone_id
  pattern = "${var.app.domain}${var.app.api_prefix}/*"
  script  = cloudflare_workers_script.api_proxy.script_name
}

# 정적 웹 — 번들은 react 쪽 워크플로가 직접 올린다
resource "cloudflare_pages_project" "web" {
  account_id        = var.cloudflare_account_id
  name              = "${local.name}-web"
  production_branch = "main"
}

resource "cloudflare_pages_domain" "web" {
  account_id   = var.cloudflare_account_id
  project_name = cloudflare_pages_project.web.name
  name         = var.app.domain
}

resource "cloudflare_dns_record" "web" {
  zone_id = var.cloudflare_zone_id
  name    = var.app.domain
  type    = "CNAME"
  content = "${cloudflare_pages_project.web.name}.pages.dev"
  proxied = true
  ttl     = 1
}

# 파일 저장소 — 키(S3 API)는 사람이 만든다
resource "cloudflare_r2_bucket" "files" {
  account_id = var.cloudflare_account_id
  name       = "${local.name}-files"
  location   = "apac"
}

resource "cloudflare_r2_bucket_cors" "files" {
  account_id  = var.cloudflare_account_id
  bucket_name = cloudflare_r2_bucket.files.name

  rules = [{
    allowed = {
      methods = ["GET", "PUT", "HEAD"]
      origins = ["https://${var.app.domain}"]
      headers = ["*"]
    }
    expose_headers  = ["ETag"]
    max_age_seconds = 600
  }]
}
