# kotlin-skeleton

Kotlin + Spring Boot 백엔드 스켈레톤. 새 API 프로젝트 시작점.

## 무엇이 들어 있나 / 새 프로젝트 3단계

- **들어 있는 것**: 골라 쓰는 모듈들(웹 기반 · 인증 · DB · 알림 · 저장소 · 결제 · Redis · 스케줄러 …), 스타터 앱 `apps/api`, 모든 모듈을 합친 데모 `apps/workbench`. 모듈마다 한 쪽짜리 문서: [`docs/modules/`](docs/modules/README.md).
- **규칙**: 모듈 하나 = 의존성 한 줄 (`implementation(project(":modules:x"))`). 모듈은 메커니즘만 주고 프레임워크 기본값을 바꾸지 않는다 — 선택은 앱 yml 이 하고, 모듈 내부는 고치지 않는다.
1. **고른다** — LLM · 사람 모두 먼저 [`llms.txt`](llms.txt)(결정표: 필요한 것 → 모듈 · `new-project.sh` 조각)를 읽는다. 전체 목록은 [`docs/capabilities.md`](docs/capabilities.md)(정본 `capabilities.json`), 제품 한 문단에서 돌아가는 서버까지의 순서와 작업 예는 [`docs/new-project-recipe.md`](docs/new-project-recipe.md). 그다음 [모듈 색인](docs/modules/README.md)에서 필요한 모듈을 확인하고, 문서의 "부팅에 필요한 것" 을 확인한다.
2. **찍는다** — `scripts/new-project.sh <dir> <package> <prefix> <ClassPrefix> --modules a,b` (복사 · 모듈 가지치기 · rename 을 한 번에; 규칙은 [docs/minimal-composition.md](docs/minimal-composition.md) §5).
3. **돌린다** — `cd <dir> && ./gradlew build`, 로컬 실행은 compose 로 DB 를 띄운 뒤 `./gradlew :apps:api:bootRun --args='--spring.profiles.active=local'`. 모듈이 더 필요하면 `apps/api/build.gradle.kts` 에 한 줄.

## Module Layout

This skeleton uses coarse-grained Gradle modules.

```text
apps/
  api                 # STARTER: minimal runnable app (platform, auth, JDBC + PostgreSQL + Flyway, time). Copy this to start a project
  workbench           # DEMO: every module together + sample endpoints under /api/v1/skeleton/** (the react-skeleton workbench UI calls it)

modules/
  platform            # web, errors, trace/logging, shared infrastructure
  auth                # stateless auth, JWT, dev-login, break-glass access
  auth-social         # optional social-login extension for auth
  auth-social-google  # optional Google OAuth provider client
  auth-social-kakao   # optional Kakao OAuth provider client
  auth-social-naver   # optional Naver OAuth provider client
  async              # optional context-propagating @Async executor and task groups
  config-aws-ssm      # optional AWS SSM Parameter Store property loading
  event-kafka         # optional Kafka event publishing adapter
  idempotency          # optional Idempotency-Key support for command endpoints
  notification         # optional notification contracts and local broker
  notification-slack   # optional Slack webhook alerts and exception notices
  notification-sse     # optional server-to-web SSE notification delivery
  notification-websocket # optional STOMP/WebSocket notification delivery
  notification-mail    # optional SMTP sending (spring.mail.* + skeleton.notification-mail.*)
  payment             # optional provider-neutral payment contracts
  payment-toss        # optional Toss payment provider
  payment-stripe      # optional Stripe payment provider
  persistence-jpa      # optional JPA audit timestamp support
  persistence-jdbc     # optional Spring Data JDBC audit timestamp support + SqlDialect strategy
  db-postgresql        # dialect module (default): driver, Flyway support, SqlDialect, time conversions
  db-mysql             # dialect module (alternative) — an app assembles exactly one db-* module
  migration            # common: skeleton.migration, guard against DB-wiping settings outside allowed profiles
  migration-flyway     # Flyway implementation: opt-in local clean, V<UTC 14 digits> naming check (no Flyway defaults changed)
  persistence-jooq     # optional jOOQ with DDL-file code generation (no DB at build), UTC Instant converter, audit listener
  redis-core          # optional Redis connection, templates, key prefixing
  redis-lock          # optional Redis-backed distributed locks
  redis-cache         # optional Redis cache manager defaults
  redis-rate-limit    # optional Redis-backed rate-limit store
  scheduler           # optional annotation-driven scheduler
  time                # optional global-time capability: viewer zone/locale, ZonedMoment, dual formatting, country -> zone
  storage             # optional storage contracts and file validation
  storage-s3          # optional S3/R2 storage adapter (presigned + server-side put, cache headers, batch delete)
  job-queue-jdbc      # optional DB-table retry queue (FOR UPDATE SKIP LOCKED, backoff, dead-letter) — no Redis
  board               # optional board: posts, nested comments, reaction types set by config (no schema change), moderation — storage in board-jdbc (list both)
  board-jdbc          # PostgreSQL / MySQL storage for board
  captcha-turnstile   # optional Cloudflare Turnstile token verification
  alert               # optional owner alerts (Discord-style webhook, optional mail): 5xx surge, startup failure, dead job; the error-reporting answer (no Sentry)
  alert-jdbc          # optional DB ledger for alert: fold repeats across instances and restarts (PostgreSQL / MySQL)
```

Use modules as capability choices:

- `apps/api` is the **starter**: the smallest composition that builds and boots (`platform`, `auth`, `persistence-jdbc`, `db-postgresql`, `migration-flyway`, `time`), one `HelloController`, a short `application.yml`. A real service starts here and adds one dependency line per module it needs (`docs/minimal-composition.md`).
- `apps/workbench` is the **demo**: it depends on every module, keeps the sample controllers (`/api/v1/examples/*`, `/api/v1/skeleton/*`) and the integration tests that prove the modules coexist. Nothing is copied from it into a product; it is also the backend of the `react-skeleton` workbench UI.
- Modules are never reached through component scan. Both apps live in a sub-package of the root (`dev.sumin.skeleton.app.api`, `dev.sumin.skeleton.app.workbench`), so `@SpringBootApplication` scans only app code, and every module registers its beans through its `AutoConfiguration`. A module's controllers/filters/advice therefore need no app-side wiring, and `skeleton.<module>.enabled=false` removes the module cleanly.
- `modules/platform` is the shared foundation for most apps. It also contributes default OpenAPI metadata, standard response/error schemas, web policy defaults, outbound HTTP client scaffolding, and trace header documentation.
  See `docs/logging.md` for stable `skeleton.debug.*` logger categories.
- `modules/auth` is included when the app needs authentication. Its default beans are Spring Boot auto-configuration defaults, so an app can replace `AuthAccountRepository`, `SecurityFilterChain`, token service, or filters with its own beans. It also contributes JWT bearer security metadata to OpenAPI.
- `modules/auth-social` is included when the app needs social login. Its default beans are also auto-configuration defaults, so account links, provisioning policy, and the social auth handler can be replaced. It also contributes the social-login endpoint to OpenAPI.
- `modules/auth-social-google`, `modules/auth-social-kakao`, and `modules/auth-social-naver` are optional provider clients. Add only the provider modules an application actually needs.
- `modules/async` is included when app/background work needs MDC and SecurityContext propagation across `@Async` or `CompletableFuture` work. It contributes `skeletonAsyncTaskExecutor`, `AsyncContextTaskDecorator`, and `AsyncTaskGroup`. See `docs/async.md`.
- `modules/idempotency` is included when command endpoints need `Idempotency-Key` protection. It contributes the `@IdempotentOperation` annotation, request fingerprinting, replay headers, an in-memory default store, and OpenAPI header documentation.
- `modules/crypto` is included when the app needs recoverable AES-GCM text encryption for persisted or transported secrets. It provides key-id envelopes, URL-safe opaque tokens, and opt-in persistence converters; redaction still handles logs and alerts.
- `modules/notification` is included when the app needs server-side notification publishing. `modules/notification-sse`, `modules/notification-websocket`, and `modules/notification-slack` add delivery/alert channels.
- `modules:redis-*`, `modules:storage-*`, `modules:payment-*`, `modules:event-kafka`, and `modules:scheduler` are optional capability bundles. `apps/workbench` includes them to prove they can coexist. Each boots without configuration and without its infrastructure (a test per module proves it); features that need keys or servers are enabled by setting them.
- `modules/persistence-jpa` and `modules/persistence-jdbc` are optional persistence adapters. Both use the same platform audit-time contract while keeping JPA/JDBC annotations and lifecycle behavior inside the selected persistence module. Instants are bound through `SqlDialect` from the dialect module the app assembles (`db-postgresql` default, `db-mysql` also forces the MySQL session to UTC); see `docs/time.md`.
- `modules/persistence-jooq` is the jOOQ alternative: code is generated from the Flyway migration folder with `DDLDatabase`, so builds need no database. See `docs/persistence-jooq.md`. Schema without Flyway: `docs/schema-management.md`.
- `modules/job-queue-jdbc` is included when work must be retried durably without Redis. See `docs/job-queue-jdbc.md`.
- `modules/alert` (+ `alert-jdbc`) tells the owner when production breaks — off until `skeleton.alert.webhook-url` is set. See `docs/alert.md`.
- `modules/notification-mail` (`docs/notification-mail.md`) and `modules/captcha-turnstile` (`docs/captcha-turnstile.md`) are off by default and only appear when their properties are set.
- HTML pages next to the API: see `apps/workbench` `api/pages/PagesController.kt` — public endpoints via `PublicEndpointContributor`, HTML error handling via a page-scoped `@ControllerAdvice`; rate limiting is `/api/**` only by default.
- Starting a new project from this skeleton: `scripts/rename-skeleton.sh` + `docs/minimal-composition.md`.
- `modules/time` is included when the app shows times to people in different time zones or stores scheduled local times (deadlines, event starts). It provides `TimeContext` (viewer zone/locale: account preference → `X-Time-Zone` / `Accept-Language` → default), `ZonedMoment` (local time + zone as the source of truth, derived instant), `TimeFormatter.dual`, and `CountryTimeZones`. See `docs/time.md`.

Fine-grained details such as JWT, password login, OAuth, or dev login live as packages inside their capability modules unless they grow into provider-level integrations.

## Quick start (starter)

Full stack in one line (stamped project with the React skeleton's project next to it as `../web`): `scripts/dev.sh` — containers (PostgreSQL, and the local S3 when `storage-s3` is in) → backend → `../web`'s `pnpm dev`. Details in the react-skeleton README "백엔드와 나란히". Piece by piece:

```bash
docker compose up -d postgres            # PostgreSQL 18 on 127.0.0.1:5432 (db/user app, password dev)
./gradlew :apps:api:bootRun --args='--spring.profiles.active=local'
curl -s localhost:8080/api/v1/hello
curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"user@example.com","password":"password"}'
./gradlew build                          # everything: all modules, both apps (Testcontainers needs Docker)
```

`docker compose up --build` builds `:apps:api` (the starter) into the image. The same `Dockerfile` is the deployable image of the homeserver deploy contract (`APP=api|sample`, wget healthcheck, stdout logs): see [docs/deploy.md](docs/deploy.md). Optional infrastructure for the modules that need it sits behind compose profiles and does not start with a plain `docker compose up`: `--profile redis` (redis-*), `--profile kafka` (event-kafka), `--profile mail` (notification-mail, Mailpit UI on :8025), `--profile mysql` (db-mysql). Per-module environment variables are in the commented sections of `.env.example`.

## 새 프로젝트 시작

한 줄이면 된다. 이 레포를 새 디렉토리로 복사하고, 고른 모듈만 남기고, 패키지 · 설정 접두사 · 클래스 접두사를 바꾼다.

```bash
scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix> \
    [--modules a,b,c] [--db postgresql|mysql] [--with-workbench]
```

```bash
# 스타터 그대로 (platform, auth, persistence-jdbc, db-postgresql, migration-flyway, time)
scripts/new-project.sh ~/work/ovation dev.sumin.ovation ovation Ovation

# 모듈 더하기 — 한 모듈 = 의존성 한 줄 + (바꿀 때만) 설정 몇 줄
scripts/new-project.sh ~/work/ovation dev.sumin.ovation ovation Ovation \
    --modules job-queue-jdbc,notification-mail,storage-s3,scheduler

# MySQL 로: db-mysql, datasource URL, compose, Testcontainers, 첫 마이그레이션 폴더까지 바뀐다
scripts/new-project.sh ~/work/ovation dev.sumin.ovation ovation Ovation --db mysql --modules job-queue-jdbc
```

- 모듈은 스타터의 모듈 + `--modules` 를 모듈끼리의 `project(":modules:x")` 의존으로 **닫은** 집합이다 (`storage-s3` → `storage`; `crypto` 는 컴파일 전용이라 소스만 따라오고 앱의 런타임에는 없다). 테스트에만 필요한 모듈 의존도 따라오고 이유가 출력된다. 모르는 모듈 이름이면 유효한 목록과 함께 exit 2.
- 고르지 않은 모듈은 디렉토리, `settings.gradle.kts` include, `docs/config/modules/<m>.yml`, `docs/modules/<m>.md`(와 색인 행), 루트 `dbTestModules`, 스타터 테스트의 부재 단언에서 모두 빠진다. `--with-workbench` 는 워크벤치가 모든 모듈을 쓰므로 모든 모듈이 남고 PostgreSQL 전용이다.
- `--modules` 로 요청한 모듈은 `apps/api/build.gradle.kts` 에 `implementation(project(":modules:<m>"))` 한 줄이 생기고, `docs/config/modules/<m>.yml` 이 `apps/api/src/main/resources/application.yml` 끝의 `new-project: module config blocks` 구역에 **주석으로** 붙는다. 모듈은 기본값으로 동작하니 바꿀 키만 주석을 풀어 위 설정에 합친다.
- 끝에 `scripts/rename-skeleton.sh` 가 돌고 잔여 흔적이 있으면 실패한다. 대상 디렉토리 밖에는 아무것도 쓰지 않고, 대상이 이미 있으면 거부한다.
- 시험: `scripts/test-new-project.sh` (빠른 검사 — `./gradlew check` 가 돈다) / `--full` (네 조합을 찍어 각각 `./gradlew build`, Docker 필요 — `.github/workflows/new-project.yml`).

- 찍힌 프로젝트에는 배포 선언 `deploy/app.yaml`(이름 · 이미지 · `env_prefix` · DB · Redis 를 채운다), GHCR 이미지 워크플로(`v*` 태그 → `ghcr.io/<owner>/<name>-api:v…` · `sha-…`, latest 없음), 계약 증명 `scripts/test-deploy-contract.sh` 가 따라온다 — **[배포 계약 · 배포 가드 · 모듈별 비밀: docs/deploy.md](docs/deploy.md)**.

수동으로 하려면: 레포를 복사해 `scripts/rename-skeleton.sh dev.sumin.ovation ovation Ovation`, 쓰지 않는 모듈과 `apps/workbench` 를 지우고 `docs/minimal-composition.md` 를 따른다.

<!-- sample:start -->
## 샘플 앱 — 새 기능을 어떻게 얹는지 (`apps/sample`)

"무엇을 받는 건가" 를 한눈에 보려면 `apps/sample` 을 본다: 로그인한 사람이 **노트**(첨부 · 고정 · 상태)를 관리하는 제품 모양의 작은 앱이다 — 스타터에 모듈 몇 줄(`idempotency` · `notification-jdbc/sse` · `storage-s3` · `job-queue-jdbc`)과 도메인 하나만 얹었다.
REST 엔드포인트 · 알림(받은편지함 + SSE) · 첨부 업로드 · 내보내기 잡 · 검증 에러 · 멱등 생성 · 소유자 확인과 그 통합 테스트까지 한 조각이 어떤 순서로 조립됐는지 [`docs/sample.md`](docs/sample.md)에 파일 단위로 적었다.

```bash
scripts/dev-sample.sh      # DB + 로컬 S3 → 백엔드(apps/sample) → 옆 ../react-skeleton/apps/sample  (user@example.com / password)
```

짝 프론트(react-skeleton `apps/sample`)가 같은 계약으로 화면을 만든다. 샘플은 `scripts/new-project.sh` 로 찍을 때 **기본으로 빠진다** — 같이 보고 싶으면 `--with-sample`.

<!-- sample:end -->
## Composition Workbench

`apps/workbench` is the backend module assembly workbench (the demo; the starter is `apps/api`). Run it for the React skeleton's workbench UI:

```bash
docker compose up -d postgres
./gradlew :apps:workbench:bootRun   # port 8080 — stop the starter first. Without a profile no Redis/Kafka/S3 is needed (the local profile turns redis-lock on)
```

It intentionally depends on the skeleton capability modules, then uses properties to decide which runtime integrations are active. It intentionally depends on the skeleton capability modules, then uses properties to decide which runtime integrations are active.

- `GET /api/v1/skeleton/modules` returns the current module catalog as a standard list response.
- `GET /api/v1/skeleton/redis/key?value=orders:1` proves `redis-core` key prefixing works without pinging Redis.
- `POST /api/v1/skeleton/storage/validate` proves storage file validation wiring.
- `GET /api/v1/skeleton/storage/public-url?key=images/cat.png` proves the configured storage public URL resolver without requiring a product endpoint.
- `POST /api/v1/skeleton/notifications` publishes a provider-neutral notification event so SSE/Slack/WebSocket delivery modules can subscribe. (The recipient's inbox endpoints, `/api/v1/notifications`, are not a workbench sample any more — `modules/notification` opens them for every app; so does `modules/storage` for `/api/v1/storage`.)
- Realtime notification workbench: `docs/notification-websocket.md` shows how to run `apps/workbench` with SSE/WebSocket enabled and verify it from `react-skeleton`.
- `GET /api/v1/skeleton/async/probe` proves trace/run/account MDC propagation into the async executor.

These endpoints require auth by default. They are development/workbench affordances, not product APIs.

Adding a module turns it on, except where it would fail or hang boot without external infrastructure or required config — those degrade safely or stay off until configured (`docs/minimal-composition.md` §3 lists what each module needs to boot). The workbench `application*.yml` therefore carries only the values that differ from module defaults: rate limiting keeps the in-memory store, WebSocket stays off (it adds a notification subscriber), provider payment modules need keys. The `local`, `dev`, `staging` and `prod` profiles turn the Redis lock startup check on (`skeleton.redis-lock.startup-check.enabled`), so those profiles need a reachable Redis unless overridden for tests.

## Auth Capability

`modules/auth` provides stateless backend auth defaults:

- `POST /api/v1/auth/login` issues a JWT bearer token.
- `GET /api/v1/auth/me` returns the authenticated `CurrentPrincipal`.
- JWT claims use `sub=accountId`, `username`, `email`, `roles`, `iss`, `iat`, and `exp`.
- Successful auth responses use the shared success envelope, and auth failures return the shared `ApiError` shape with trace/span fields.

Development seed accounts:

| accountId | username | email | password | roles |
| --- | --- | --- | --- | --- |
| `acc_user` | `user` | `user@example.com` | `password` | `USER` |
| `acc_admin` | `admin` | `admin@example.com` | `password` | `USER`, `ADMIN` |

Example:

```bash
curl -s http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"password"}'
```

Local/dev header login can be enabled only for `local` or `dev` profiles:

```yaml
skeleton:
  auth:
    dev-login:
      enabled: true
```

Supported headers are `X-Dev-Account-Id`, `X-Dev-Username`, and `X-Dev-Email`. Roles are always loaded from `AuthAccountRepository`; role headers are ignored.

Break-glass access is disabled by default and is intended for audited emergency access:

```yaml
skeleton:
  auth:
    break-glass:
      enabled: true
      secret: ${BREAK_GLASS_SECRET}
      allowed-account-ids:
        - acc_admin
```

Requests must include `X-Break-Glass-Secret`, `X-Break-Glass-Reason`, and `X-Break-Glass-Account-Id`. In `prod` and `staging`, startup validation requires a nonblank secret and allowlist. Keep YAML thin; inject secrets through environment variables.

### Protected profiles — what refuses to boot

The built-in defaults are for development: the JWT secret `dev-local-jwt-secret-change-me-32-bytes` and the seed accounts above. When any active profile is in `skeleton.auth.protected-profiles` (default `prod`, `staging`), startup fails with a message naming what to set if:

| Rule | Fails when | Fix |
| --- | --- | --- |
| JWT secret | `skeleton.auth.jwt.secret` is blank, the built-in default, or shorter than 32 bytes (HS256) | set `skeleton.auth.jwt.secret` (env `JWT_SECRET` or `SKELETON_AUTH_JWT_SECRET`) to a random value of at least 32 bytes |
| Account store | the built-in in-memory `AuthAccountRepository` (seeds `user/password`, `admin/password`) is the one in use | register your own `AuthAccountRepository` bean |
| Dev login | `skeleton.auth.dev-login.enabled=true` | leave it off |
| Break-glass | enabled without a secret, or without `allowed-account-ids` | set both |

Setting `skeleton.env=stage|prod` (env `SKELETON_ENV`, opt-in — see [docs/deploy.md](docs/deploy.md)) applies the same rules without a profile; the rules are `DeployGuard` beans listed in a startup log summary.

`local`, `dev`, `test` and no profile keep working unchanged (the starter's tests log in with the seed user). To protect other profile names, set `skeleton.auth.protected-profiles`. A starter run with `--spring.profiles.active=prod` will not boot until you add an `AuthAccountRepository` — that is the point.

## Auth Social Capability

`modules/auth-social` is an optional social-login extension for `modules/auth`.

- `POST /api/v1/auth/social/{provider}/login` exchanges a frontend-provided authorization code through an enabled provider.
- The `value` response payload is the same as password login: bearer token, expiration, and `CurrentPrincipal`.
- Provider identity maps to an internal account through `OAuthAccountLinkRepository`.
- Roles always come from `AuthAccountRepository`, not provider profile data.
- Google, Kakao, and Naver are separate optional Gradle modules. Add a provider module to the application build only when that provider is needed:

```kotlin
dependencies {
    implementation(project(":modules:auth-social"))
    implementation(project(":modules:auth-social-google"))
}
```

Provider credentials should be supplied through environment variables:

```yaml
skeleton:
  auth-social:
    providers:
      google:
        enabled: true
        client-id: ${GOOGLE_OAUTH_CLIENT_ID}
        client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET}
        redirect-uri: ${GOOGLE_OAUTH_REDIRECT_URI}
```

Provider defaults use the public OAuth token/profile hosts. Override `token-base-url`, `profile-base-url`, `token-path`, or `profile-path` only for tests, proxies, or vendor-specific gateway routing.

## Notification Capability

`modules/notification` is the provider-neutral notification base:

- `NotificationEvent` carries `topic`, `type`, `severity`, optional text, optional structured payload, and timestamps.
- `NotificationPublisher` publishes events and returns local delivery counts.
- `NotificationSubscriptionRegistry` lets delivery modules subscribe to topics.
- `InMemoryNotificationBroker` is the default single-node skeleton broker and can be replaced by Redis, Kafka, database fanout, or vendor-specific delivery modules.

`modules/notification-sse` is optional web delivery:

```kotlin
dependencies {
    implementation(project(":modules:notification"))
    implementation(project(":modules:notification-sse"))
}
```

```text
GET /api/v1/notifications/sse?topic=runs&topic=orders
```

The SSE endpoint produces `text/event-stream`, sends an initial `connected` event, and then emits each `NotificationEvent` with SSE `id=event.id` and `name=event.type`. It is not public by default. Enable `skeleton.notification.sse.public-endpoint=true` only when the app handles access through cookies, gateway policy, or scoped topic tokens.

## Web Platform Capability

`modules/platform` provides inbound web policy defaults:

- Forwarded header support is enabled by default, so generated absolute URLs respect reverse proxy headers.
- Security headers are enabled by default: content type options, frame options, referrer policy, permissions policy, and HSTS on secure requests.
- CORS is scaffolded but disabled by default. Enable `skeleton.web.cors.enabled=true` for local split frontend/backend development.
- Rate limit is scaffolded but disabled by default. Enable `skeleton.web.rate-limit.enabled=true` and override `RateLimitStore` or `RateLimitKeyResolver` for Redis/account/API-key policies.
- Public endpoints are contributed through `PublicEndpointContributor`; `modules/auth` reads the registry and applies `permitAll`.

Example public endpoint contribution:

```kotlin
@Bean
fun publicEndpoints(): PublicEndpointContributor =
    PublicEndpointContributor { registry ->
        registry.add("GET", "/api/v1/public/catalog/**")
    }
```

Outbound HTTP calls should use `ExternalHttpClient` instead of raw `WebClient` construction:

```kotlin
externalHttpClient.post(
    clientName = "payment",
    path = "/v1/payments",
    body = request,
    responseType = PaymentResponse::class.java,
) {
    header("Idempotency-Key", idempotencyKey)
    timeout(Duration.ofSeconds(3))
}
```

The default client supports `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`, propagates trace headers, applies timeout/error mapping, and can be customized per named client through `ExternalHttpClientCustomizer` or `ExternalHttpErrorMapper`.
It also supports `postForm(...)` for OAuth/payment-style form-urlencoded APIs and per-call `baseUrl(...)` overrides for calls whose token/profile hosts differ.

## Idempotency Capability

`modules/idempotency` is optional command-endpoint protection:

```kotlin
dependencies {
    implementation(project(":modules:idempotency"))
}
```

Mark endpoints that must not execute twice:

```kotlin
@PostMapping("/orders")
@CreatedOperation
@IdempotentOperation
fun createOrder(
    @Valid @RequestBody request: OrderCreateRequest,
) = Response.created(location = location, value = order)
```

- `@IdempotentOperation` means `Idempotency-Key` is required.
- Missing key returns `400 ApiError`.
- Same key + same method/path/query/body fingerprint replays the first stored response.
- Same key + different fingerprint returns `409 ApiError`.
- Same key while the first request is still processing returns `409 ApiError`.
- Default storage is in-memory and replaceable by defining an `IdempotencyStore` bean.
- Response headers include `Idempotency-Key` and `X-Idempotency-Replayed`.

## Persistence Audit Capability

`modules/platform` defines the shared time contract:

- `TimeProvider` is auto-configured by default and returns UTC `Instant` values truncated to microsecond precision (PostgreSQL `timestamptz`, MySQL `datetime(6)`).
- `BaseAuditTimestamps` is the persistence-neutral contract for `createdAt`, `updatedAt`, and optional `deletedAt`.
- General event timestamps use `Instant`, are stored as UTC, and are serialized as ISO-8601 `...Z` values.

JPA applications can add:

```kotlin
dependencies {
    implementation(project(":modules:persistence-jpa"))
}
```

Then either embed the JPA audit value object directly:

```kotlin
@Entity
class OrderEntity(
    @Embedded
    var audit: AuditTimestamps = AuditTimestamps(),
)
```

or extend the convenience base class:

```kotlin
@Entity
class OrderEntity : BaseJpaEntity()
```

`modules/persistence-jpa` owns `@Embeddable`, `@Column(name = "...", columnDefinition = "DATETIME(6)")`, `@PrePersist`, and `@PreUpdate` behavior.

Spring Data JDBC applications can add:

```kotlin
dependencies {
    implementation(project(":modules:persistence-jdbc"))
}
```

Use the JDBC audit value object with `@Embedded` and implement `JdbcAuditable` to opt into the auto-configured callback:

```kotlin
@Table("orders")
data class OrderEntity(
    @Id
    val id: Long?,
    @Embedded.Nullable
    override val audit: AuditTimestamps = AuditTimestamps.now(),
) : JdbcAuditable {
    override val isNew: Boolean
        get() = id == null

    override fun withAudit(audit: AuditTimestamps): OrderEntity =
        copy(audit = audit)
}
```

`modules/persistence-jdbc` owns Spring Data Relational `@Column` mapping and updates audit fields through `JdbcAuditBeforeConvertCallback`.

## 스택

- Kotlin 2.3.21 / JDK 21
- Spring Boot 4.1.1
- Spring Data JDBC + Flyway (UTC timestamp versions, `docs/schema-management.md`)
- PostgreSQL 18 (default, `modules:db-postgresql`) or MySQL 8.4 (`modules:db-mysql`)
- Spring MVC server + WebClient outbound client
- Gradle (Kotlin DSL)

## 빠른 시작

```bash
# 로컬 DB만 띄우기
docker compose up -d postgres

# 앱 실행 (호스트 JDK 사용)
./gradlew bootRun
```

`http://localhost:8080/health` → `{"status":"UP"}`

풀스택 컨테이너 기동:

```bash
docker compose up -d
```

## REST 컨벤션

- 기본 API 네임스페이스: `/api/v1/*` (컨트롤러에서 `@RequestMapping("/api/v1/...")`)
- 헬스체크: `/health` (Spring Boot Actuator)
- 성공 응답은 `modules/platform` 의 envelope DTO를 사용한다.
  - 값 없음: `Response.ok()` → `{ "meta": ... }`
  - 단건: `Response.ok(dto)` → `{ "value": ..., "meta": ... }`
  - 목록: `Response.ok(items)` → `{ "values": [...], "meta": ... }`
  - 페이지: `Response.ok(items, pagination)` → `{ "values": [...], "pagination": ..., "meta": ... }`
  - 커서: `Response.ok(items, hasNext) { it.id }` → `{ "values": [...], "cursor": ..., "meta": ... }`
- 생성/명령성 작업은 `Response` helper 와 표준 OpenAPI annotation 을 같이 사용한다.
  - 생성: `@CreatedOperation` + `Response.created(location, dto)` → `201 Created`, `Location`, `{ "value": ..., "meta": ... }`
  - 비동기 시작: `@AcceptedOperation` + `Response.accepted(dto)` → `202 Accepted`, `{ "value": ..., "meta": ... }`
  - 삭제/토글/명령 완료: `@NoContentOperation` + `Response.noContent()` → `204 No Content`
- 페이지 요청은 `@Valid @ParameterObject @ModelAttribute pageQuery: PageQuery` 를 기본으로 쓴다. 기본값은 `page=0`, `size=20`, 최대 `size=100` 이다.
- `meta` 에는 현재 요청의 `traceId`, `spanId`, `timestamp` 가 들어간다.
- 에러 응답은 `ApiError` shape를 사용하고, `code` 는 `AUTH.INVALID_CREDENTIALS` 같은 안정적인 namespaced string 으로 내려간다. 자세한 규칙은 [docs/errors.md](docs/errors.md)를 본다.
- 요청 DTO는 Jakarta Bean Validation constraint 를 사용한다. Validation 실패는 `400 Validation failed` 와 `errors[]` 로 응답한다.
  - 예: `{ "field": "email", "code": "Email", "message": "must be a well-formed email address" }`
- `/api/v1/examples/*` 는 REST operation contract 샘플이다. 실제 프로젝트에서는 같은 패턴을 복사한 뒤 삭제하거나 도메인 예제로 교체한다.

## Swagger / OpenAPI

- API spec JSON: `/api/v1/docs`
- Swagger UI: `/api/v1/docs/ui`
- 명세는 code-first 로 생성한다. DTO와 controller/route 반환 타입이 원천이고, 별도 문서를 손으로 맞추지 않는다.
- Bean Validation constraint 는 OpenAPI request schema 에 자동 반영한다.
- 반복되는 명세는 capability module auto-configuration 이 기여한다.
  - `modules/platform`: API info, `ApiError`, `ResponseMeta`, `PaginationMeta`, trace headers, common error responses, 201/202/204 operation responses
  - `modules/auth`: `bearerAuth` JWT security scheme and authenticated endpoint security responses
  - `modules/auth-social`: functional social-login route documentation
- 엔드포인트별 비즈니스 의미가 필요할 때만 `@Operation`/`@Schema` 같은 annotation 을 추가한다. 생성/비동기/204 같은 반복 status 명세는 `@CreatedOperation`, `@AcceptedOperation`, `@NoContentOperation` 을 우선 사용한다.

## 사용법

이 레포는 **GitHub Template**. 새 프로젝트 시작:

1. GitHub 레포 페이지 → **Use this template** 버튼
2. 또는 `gh repo create <name> --template sumin/kotlin-skeleton --private`
