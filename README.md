# kotlin-skeleton

Kotlin + Spring Boot 백엔드 스켈레톤. 새 API 프로젝트 시작점.

<!-- skeleton-only:start -->
## 기능 한눈에

상태 표기 — **기본**: 스타터(`apps/api`)에 이미 들어 있다 · **선택**: 의존성 한 줄로 얹는다(필요한 키·서버는 모듈 문서의 "부팅에 필요한 것") · **꺼진 채 시작**: 코드는 있어도 설정 스위치를 켜야 동작한다(안 켜면 이전과 한 글자도 다르지 않다) · **제한**: 아래에 적은 한계가 있다.
정본 목록은 [`docs/capabilities.md`](docs/capabilities.md)(`capabilities.json` 에서 생성), 모듈마다 한 쪽 문서는 [`docs/modules/`](docs/modules/README.md), 바뀐 이력은 [`CHANGELOG.md`](CHANGELOG.md) 의 `[Unreleased]`.

| 영역 | 들어 있는 것 | 상태 | 자세히 |
|---|---|---|---|
| **인증 · 계정** | 이메일 + 비밀번호 로그인(JWT), 가입(메일로 받은 코드 확인), 비밀번호 재설정 · 이메일 변경, 탈퇴(행은 남기고 익명화 · 유예 기간), 리프레시 토큰 회전 · 재사용 탐지 · 로그인 기기 목록 | 기본 (메일을 실제로 보내려면 `notification-mail` 추가) | [accounts](docs/accounts.md) · [HTTP 계약](docs/account-http-contract.md) |
| | 메일 링크 로그인(매직 링크) · 운영자 계정 관리(목록 · 정지 · 역할 · 삭제 복구) | 선택 · 운영자 경로는 꺼진 채 시작 | [auth-magic-link](docs/modules/auth-magic-link.md) |
| | 로그인 아이디(이메일이 바뀌어도 안 바뀌는 비공개 아이디) · 이메일 필수 + 받는 시점(로그인 직후 / 처음 쓰기 때) · 연락받을 이메일 | 꺼진 채 시작 | [accounts](docs/accounts.md) |
| | 소셜 로그인: Google · Kakao · Naver · X(PKCE) · LINE · 범용 OIDC. 같은 이메일 자동 병합은 제공자가 그 주소의 권위자일 때만 | 선택 (실제 제공자 설정 · 확인 스크립트는 문서) | [auth-social](docs/modules/auth-social.md) · [real-provider-setup](docs/real-provider-setup.md) |
| | 작성자 프로필(한 계정이 닉네임 여러 개, 서로 이어지지 않는다) · 게시판(글 · 대댓글 · 설정으로 늘리는 반응) | 선택 | [author-profile](docs/modules/author-profile.md) · [board](docs/modules/board.md) |
| | 약관 · 개인정보 처리방침 · 동의 기록(판 · 시행일 · 재동의). 예시 TEMPLATE 문서는 prod 에서 막힌다 | 기본 (문서 본문은 앱이 싣는다) | [legal](docs/legal.md) |
| **결제 · 구독 (토스, 한국)** | 결제 장부: 주문 · 환불 · 변경 이력 · 웹훅 수신함 · 사용자 / 운영자 HTTP. 금액은 앱의 `PaymentCatalog` 만 정하고, 같은 요청은 같은 줄(멱등), 결제사 응답을 모르면 다시 보내지 않고 조회로 맞춘다 | 선택 (`payment` + `payment-jdbc` + `payment-toss`, 운영자 경로는 꺼진 채 시작) | [payment-http-contract](docs/payment-http-contract.md) · [payment](docs/modules/payment.md) |
| | 토스 단건 결제 · 전액 / 부분 환불. 환불 요청(본인은 요청만) → 운영자가 **금액을 고쳐 승인**하거나 직접 환불, 결제사가 끝내 답하지 않은 구독 결제를 운영자가 「결제 안 됨」으로 닫기 | 선택 (토스 테스트 키로 실측한 기록: [real-provider-setup](docs/real-provider-setup.md) §11.7) | [payment-toss](docs/modules/payment-toss.md) |
| | 구독(정기결제): 카드 등록(빌링키) + 첫 결제 → 내 구독 → 해지 예약 → 기간 끝 갱신(앱이 직접 — 결제사 스케줄러를 쓰지 않는다) · 거절 시 재시도. 켜는 앱이 플랜(`SubscriptionPlans`)과 `crypto` 키를 내야 하고 없으면 기동 실패 | 꺼진 채 시작 (`skeleton.payment.subscription.enabled`) | [payment](docs/modules/payment.md) |
| | 구독 **플랜 올리기**: 남은 기간 차액만(`PRORATED_DIFFERENCE`) 또는 전액 재시작(`FULL_RESTART`) — 켜는 앱이 고른다 | 꺼진 채 시작 (`…subscription.plan-change.enabled`) | [payment-http-contract](docs/payment-http-contract.md) §12.2 |
| | **중도 해지 · 환불**과 **7일 철회**(한국 규정): 나라마다 갈아 끼우는 정책. 모듈 기본은 기간 끝에만 해지(환불 0), 내장 `korea` 는 낸 돈 − 쓴 몫(날짜 단위) − 위약금, 7일 이내 미사용이면 전액. 못 나간 환불은 운영자 「확인 필요」로 올라온다 | 꺼진 채 시작 (`…subscription.cancel.policies` 에 나라를 적는다) | [payment](docs/modules/payment.md) |
| | Stripe | 선택 · 승인 · 취소만 있다(장부 위의 구독 · 웹훅 기능은 문서에 없다) | [payment-stripe](docs/modules/payment-stripe.md) |
| **해외 결제** | **Paddle**(판매 대행 — 해외 세금을 Paddle 이 처리, 여러 통화): 서버가 Paddle 거래를 먼저 만들고(가격은 서버 설정) 결제창은 그 거래 id 만 연다 · **서명된 웹훅**(HMAC-SHA256, 시각 허용 오차)만 믿고 장부에 옮긴다 · 산 가격 대조 · 갱신은 Paddle, 해지는 Paddle API · 가격 표시용 값(클라이언트 토큰 · 환경 · 가격 id)을 `GET /config` 로 준다 | 선택 · 가짜 Paddle 서버로 e2e, Paddle 샌드박스에서 단건 · 구독 첫 결제를 실측했다(2026-10-13 — 갱신 · 해지 · 환불은 샌드박스 미실측). 한국 구매자의 Paddle 결제는 막지 않고 운영자 「확인 필요」로 표시 | [payment-paddle](docs/modules/payment-paddle.md) |
| **접속 나라로 고르기** | 서버가 접속 나라(Cloudflare `CF-IPCountry` — 클라이언트 IP 모드가 `cloudflare` 이고 믿는 프록시에서 온 것만)로 결제사와 해지 · 환불 규칙을 정한다. 요청 본문의 `provider` · `country` 는 읽지 않는다. 그 나라의 결제사가 못 열리면 다른 결제사로 넘기지 않고 503. `GET /config` 가 `country` · `provider` · `cancelPolicy` 를 알려 준다 | 꺼진 채 시작 (`skeleton.payment.routing.*`) | [CHANGELOG](CHANGELOG.md) "접속 나라로 결제사 · 해지·환불 규칙" |
| | **개발용 나라 전환**: `routing.dev-country`(모든 요청을 한 나라로) · `routing.dev-country-switch=true` 면 요청 헤더 `X-Dev-Country` 로 재기동 없이 토스 ↔ Paddle. 보호 환경(stage · prod)에서 켜져 있으면 기동 실패 | 로컬 전용 | [CHANGELOG](CHANGELOG.md) |
| **운영 · 관리** | 주인 경보: 5xx 몰림 · 기동 실패 · 죽은 작업을 Discord 식 웹훅(+메일)으로, 여러 인스턴스의 같은 경보는 한 번만(`alert-jdbc`). 에러 수집 서비스(Sentry)를 쓰지 않는 답 | 기본 · 웹훅 주소를 넣어야 켜진다 | [alert](docs/alert.md) |
| | DB 작업 큐(Redis 없이): 재시도 · 백오프 · 죽은 작업, 결제 환불 · 갱신도 이것으로 돈다 | 기본 | [job-queue-jdbc](docs/job-queue-jdbc.md) |
| | 배포 가드: 보호 환경(prod · stage)에서 개발 기본 비밀 · 시드 계정 · dev-login · 빠진 비밀이 있으면 기동 실패(`DeployGuard`). 행 id 는 시간순(`접두사 + ULID`, 열이 `uuid` 면 UUIDv7). 마이그레이션은 UTC 타임스탬프 이름 + `migrations.lock` 으로 동결 | 기본 | [deploy](docs/deploy.md) · [schema-management](docs/schema-management.md) |
| **멀티 프로젝트** | 한 프로세스에 프로젝트 여럿: 요청 Host 로 프로젝트를 고르고, 프로젝트마다 DB 스키마 · DB 계정 · 연결 풀, 로그인 설정(JWT 키 · 쿠키 · CORS · 메일 링크 · 시드)을 따로 둔다. `@ProjectApi` 로 도메인별 컨트롤러, 잡 큐 · 계정 정리 · 메모리 한도는 프로젝트마다 | **제한 · 초기** — 설정이 비면 아무것도 달라지지 않는다. 결제 · 소셜 로그인 · 메일 발신 · 파일 · 알림 웹소켓 · Redis · 캡차 · 암호화 · Kafka 는 **아직 프로젝트를 몰라서**, 서비스 중인 프로젝트가 둘 이상이면 이 모듈들이 빌드에 있는 것만으로 기동이 실패한다. 프로젝트마다 DB 계정이 필수(PostgreSQL 기준 검사) | [multi-project](docs/multi-project.md) |
| **배포 · 런타임** | 홈서버 배포 계약: 이미지 하나 + `deploy/app.yaml` 한 장, `wget` 헬스체크, `0.0.0.0`, `SPRING_DATASOURCE_*`, 클라이언트 IP 모드 | 기본 | [deploy](docs/deploy.md) |
| | **GraalVM 네이티브 이미지**: `-Pnative` 일 때만 켜지는 선택 빌드(기본 빌드는 JVM 그대로, `Dockerfile.native`). 콜드스타트(`/health` 200)가 스타터 약 0.6초(JVM 약 7.7초), 모듈을 모두 얹은 시험 조립 약 0.9초 — 맥에서 잰 리눅스 arm64 컨테이너(CPU 1 · 메모리 512MB) 값 | 선택 · **제한**: amd64(Cloud Run) 빌드는 맥 에뮬레이션으로만 돌려 봤고 실제 x86 값은 재지 않았다. 새 의존성마다 리플렉션 힌트 규칙을 지켜야 하고, 켜기 / 끄기가 빌드 때 굳는다 | [native-image](docs/native-image.md) |
| | **Cloud Run 0대 운영**: 안 쓸 때 인스턴스 0 — 작업 큐를 "요청 구동" 모드로 바꾸고 밖의 스케줄러가 깨우기 주소 `POST /internal/jobs/drain`(헤더 `X-Wake-Secret`)을 부르면 그때 밀린 일감을 처리한다 | 선택 · 레포는 앱이 지킬 것과 설정 예만 준다(실제 Cloud Run 배포는 하지 않는다) | [cloud-run-scale-to-zero](docs/cloud-run-scale-to-zero.md) |
| **기반 부품** | 표준 응답 · 에러 봉투 · OpenAPI(코드 우선) · trace 로그 · 외부 HTTP 클라이언트 / 시간대 · `ZonedMoment` / `Idempotency-Key` / AES-GCM 암호화 · JSON 도구 / 알림(받은편지함 · SSE · WebSocket · Slack · 메일) / 파일(S3 · R2 presign) / Redis(락 · 캐시 · 요청 제한) · 스케줄러 · Kafka / JPA · jOOQ(기본은 Data JDBC) · PostgreSQL 18(기본) · MySQL 8.4 | 기본 / 선택 | [modules 색인](docs/modules/README.md) |
| **견본 · 도구** | `apps/sample`: 로그인한 사람이 노트(첨부 · 알림 · 내보내기)를 관리하고 「응원하기」 결제 · 구독까지 있는 제품 모양의 앱 — 새 기능을 얹는 정본 예시. `apps/workbench`: 모든 모듈을 합친 데모. 견본 앱은 네이티브로도 빌드해 react 쪽 e2e 를 그대로 돌린다(`E2E_NATIVE=1`) | 참조용 (새 프로젝트에는 `--with-sample` 일 때만 따라간다) | [sample](docs/sample.md) · [native-image](docs/native-image.md) |
<!-- skeleton-only:end -->

## 새 프로젝트 3단계

- **들어 있는 것**: 골라 쓰는 모듈들(웹 기반 · 인증 · DB · 알림 · 저장소 · 결제 · Redis · 스케줄러 …), 스타터 앱 `apps/api`, 모든 모듈을 합친 데모 `apps/workbench`. 모듈마다 한 쪽짜리 문서: [`docs/modules/`](docs/modules/README.md).
- **규칙**: 모듈 하나 = 의존성 한 줄 (`implementation(project(":modules:x"))`). 모듈은 메커니즘만 주고 프레임워크 기본값을 바꾸지 않는다 — 선택은 앱 yml 이 하고, 모듈 내부는 고치지 않는다.
1. **고른다** — LLM · 사람 모두 먼저 [`llms.txt`](llms.txt)(결정표: 필요한 것 → 모듈 · `new-project.sh` 조각)를 읽는다. 전체 목록은 [`docs/capabilities.md`](docs/capabilities.md)(정본 `capabilities.json`), 제품 한 문단에서 돌아가는 서버까지의 순서와 작업 예는 [`docs/new-project-recipe.md`](docs/new-project-recipe.md). 그다음 [모듈 색인](docs/modules/README.md)에서 필요한 모듈을 확인하고, 문서의 "부팅에 필요한 것" 을 확인한다.
2. **찍는다** — `scripts/new-project.sh <dir> <package> <prefix> <ClassPrefix> --modules a,b` (복사 · 모듈 가지치기 · rename 을 한 번에; 규칙은 [docs/minimal-composition.md](docs/minimal-composition.md) §5).
3. **돌린다** — `cd <dir> && ./gradlew build`, 로컬 실행은 compose 로 DB 를 띄운 뒤 `./gradlew :apps:api:bootRun --args='--spring.profiles.active=local'`. 모듈이 더 필요하면 `apps/api/build.gradle.kts` 에 한 줄.

## Module Layout

This skeleton uses coarse-grained Gradle modules.

```text
apps/
  api                 # STARTER: minimal runnable app (platform, auth, accounts + sessions + legal on JDBC, PostgreSQL + Flyway, time). Copy this to start a project
  workbench           # DEMO: every module together + sample endpoints under /api/v1/skeleton/** (the react-skeleton workbench UI calls it)

modules/
  platform            # web, errors, trace/logging, shared infrastructure (+ per-project context: skeleton.projects)
  auth                # stateless auth, JWT, dev-login, break-glass access (+ login hooks, session SPI)
  auth-session        # refresh tokens (rotation + reuse detection), session list, logout
  auth-session-jdbc   # session store for PostgreSQL / MySQL
  account             # account lifecycle: sign-up, email verification, reset, change, sign-in methods, deletion, login ID, admin
  account-jdbc        # account store for PostgreSQL / MySQL
  auth-magic-link     # email-link sign-in (one more method on the same account)
  auth-social         # optional social-login extension for auth
  auth-social-google  # optional Google OAuth provider client
  auth-social-kakao   # optional Kakao OAuth provider client
  auth-social-naver   # optional Naver OAuth provider client
  auth-social-x       # optional X (Twitter) OAuth 2.0 + PKCE provider client
  auth-social-oidc    # optional generic OpenID Connect provider (several by properties, LINE preset)
  author-profile      # optional author profiles: several unlinkable nicknames per account
  author-profile-jdbc # author profile store for PostgreSQL / MySQL
  legal               # optional terms / privacy documents and consent records
  legal-jdbc          # legal store for PostgreSQL / MySQL
  async               # optional context-propagating @Async executor and task groups
  async-notification  # optional: async exceptions become notifications
  config-aws-ssm      # optional AWS SSM Parameter Store property loading
  event-kafka         # optional Kafka event publishing adapter
  idempotency         # optional Idempotency-Key support for command endpoints
  json                # optional JsonCodec, JsonDocument, versioned payloads
  crypto              # optional AES-GCM text encryption, opaque URL tokens
  notification        # optional notification contracts and local broker
  notification-jdbc   # optional DB inbox for notification
  notification-slack  # optional Slack webhook alerts and exception notices
  notification-sse    # optional server-to-web SSE notification delivery
  notification-websocket # optional STOMP/WebSocket notification delivery
  notification-mail   # optional SMTP sending (spring.mail.* + skeleton.notification-mail.*)
  payment             # optional provider-neutral payment contracts + ledger HTTP (orders, refunds, subscriptions)
  payment-jdbc        # payment ledger store for PostgreSQL / MySQL
  payment-toss        # optional Toss payment provider (one-time, billing keys)
  payment-stripe      # optional Stripe payment provider (confirm / cancel)
  payment-paddle      # optional Paddle (merchant of record) provider: server-made transactions, signed webhooks
  persistence-jpa     # optional JPA audit timestamp support
  persistence-jdbc    # optional Spring Data JDBC audit timestamp support + SqlDialect strategy + per-project routing DataSource
  db-postgresql       # dialect module (default): driver, Flyway support, SqlDialect, time conversions
  db-mysql            # dialect module (alternative) — an app assembles exactly one db-* module
  migration           # common: skeleton.migration, guard against DB-wiping settings outside allowed profiles
  migration-flyway    # Flyway implementation: opt-in local clean, V<UTC 14 digits> naming check (no Flyway defaults changed)
  persistence-jooq    # optional jOOQ with DDL-file code generation (no DB at build), UTC Instant converter, audit listener
  redis-core          # optional Redis connection, templates, key prefixing
  redis-lock          # optional Redis-backed distributed locks
  redis-cache         # optional Redis cache manager defaults
  redis-rate-limit    # optional Redis-backed rate-limit store
  scheduler           # optional annotation-driven scheduler
  time                # optional global-time capability: viewer zone/locale, ZonedMoment, dual formatting, country -> zone
  storage             # optional storage contracts and file validation
  storage-s3          # optional S3/R2 storage adapter (presigned + server-side put, cache headers, batch delete)
  job-queue-jdbc      # optional DB-table retry queue (FOR UPDATE SKIP LOCKED, backoff, dead-letter; polling or on-request wake) — no Redis
  board               # optional board: posts, nested comments, reaction types set by config (no schema change), moderation — storage in board-jdbc (list both)
  board-jdbc          # PostgreSQL / MySQL storage for board
  captcha-turnstile   # optional Cloudflare Turnstile token verification
  alert               # optional owner alerts (Discord-style webhook, optional mail): 5xx surge, startup failure, dead job; the error-reporting answer (no Sentry)
  alert-jdbc          # optional DB ledger for alert: fold repeats across instances and restarts (PostgreSQL / MySQL)
```

Use modules as capability choices:

- `apps/api` is the **starter**: the smallest composition that builds and boots **with real accounts** (`platform`, `auth`, `account-jdbc`, `auth-session-jdbc`, `legal-jdbc`, `persistence-jdbc`, `db-postgresql`, `migration-flyway`, `time` — sign-up, email verification, login, refresh tokens, password reset and deletion work out of the box; add `notification-mail` to actually send the mails), one `HelloController`, a short `application.yml`. A real service starts here and adds one dependency line per module it needs (`docs/minimal-composition.md`).
- `apps/workbench` is the **demo**: it depends on every module, keeps the sample controllers (`/api/v1/examples/*`, `/api/v1/skeleton/*`) and the integration tests that prove the modules coexist. Nothing is copied from it into a product; it is also the backend of the `react-skeleton` workbench UI.
- Modules are never reached through component scan. Both apps live in a sub-package of the root (`dev.sumin.skeleton.app.api`, `dev.sumin.skeleton.app.workbench`), so `@SpringBootApplication` scans only app code, and every module registers its beans through its `AutoConfiguration`. A module's controllers/filters/advice therefore need no app-side wiring, and `skeleton.<module>.enabled=false` removes the module cleanly.
- `modules/platform` is the shared foundation for most apps. It also contributes default OpenAPI metadata, standard response/error schemas, web policy defaults, outbound HTTP client scaffolding, and trace header documentation.
  See `docs/logging.md` for stable `skeleton.debug.*` logger categories.
- `modules/auth` is included when the app needs authentication. Its default beans are Spring Boot auto-configuration defaults, so an app can replace `AuthAccountRepository`, `SecurityFilterChain`, token service, or filters with its own beans. It also contributes JWT bearer security metadata to OpenAPI.
- `modules/account` + `account-jdbc` give the app real accounts (sign-up, verification, reset, change, deletion, admin tools, sign-in methods as rows of one `identities` table), `modules/auth-session` + `-jdbc` add refresh-token rotation and a device/session list, `modules/auth-magic-link` adds email-link sign-in — see `docs/accounts.md`.
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
./gradlew build                          # everything: all modules, both apps (Testcontainers needs Docker)
```

The starter has **no seed accounts**: create one through sign-up (`POST /api/v1/account/sign-up`, then the code that was mailed, see [docs/accounts.md](docs/accounts.md)). The ready-made `user@example.com` / `password` accounts exist only in the sample app's `local` profile (`scripts/dev-sample.sh`) and in tests.

`docker compose up --build` builds `:apps:api` (the starter) into the image. The same `Dockerfile` is the deployable image of the homeserver deploy contract (`APP=api|sample`, wget healthcheck, stdout logs): see [docs/deploy.md](docs/deploy.md). Optional infrastructure for the modules that need it sits behind compose profiles and does not start with a plain `docker compose up`: `--profile redis` (redis-*), `--profile kafka` (event-kafka), `--profile mail` (notification-mail, Mailpit UI on :8025), `--profile mysql` (db-mysql). Per-module environment variables are in the commented sections of `.env.example`.

## 새 프로젝트 시작

한 줄이면 된다. 이 레포를 새 디렉토리로 복사하고, 고른 모듈만 남기고, 패키지 · 설정 접두사 · 클래스 접두사를 바꾼다.

```bash
scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix> \
    [--modules a,b,c] [--db postgresql|mysql] [--with-workbench]
```

```bash
# 스타터 그대로 (platform, auth, account-jdbc, auth-session-jdbc, legal-jdbc, persistence-jdbc, db-postgresql, migration-flyway, time)
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

- 찍힌 프로젝트에는 배포 선언 `deploy/app.yaml`(이름 · 이미지 · `env_prefix` · DB · Redis 를 채운다), 로컬 빌드 이미지(`image_local: true`, GHCR 는 쓰지 않는다 — 이미지 워크플로는 수동 실행만 · 보류), 계약 증명 `scripts/test-deploy-contract.sh` 가 따라온다 — **[배포 계약 · 배포 가드 · 모듈별 비밀: docs/deploy.md](docs/deploy.md)**.

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

It intentionally depends on the skeleton capability modules, then uses properties to decide which runtime integrations are active.

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

Development seed accounts — these belong to the built-in in-memory `AuthAccountRepository`, which is used only when no account store module is present (e.g. `modules/auth` alone). With `account-jdbc` (the starter) there are no seeds unless you set `skeleton.account.seed.accounts`:

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

`local`, `dev`, `test` and no profile keep working unchanged (the starter's tests load seed accounts from a test-only file). To protect other profile names, set `skeleton.auth.protected-profiles`. The starter already has an account-backed `AuthAccountRepository` (`account-jdbc`), so the account-store rule is met there; a run with `--spring.profiles.active=prod` still will not boot until the JWT secret and the other guard settings (see `docs/deploy.md` §4) are set — that is the point.

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

# 앱 실행 (호스트 JDK 사용) — 스타터
./gradlew :apps:api:bootRun --args='--spring.profiles.active=local'
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
2. 또는 `gh repo create <name> --template turong92/kotlin-skeleton --private`
