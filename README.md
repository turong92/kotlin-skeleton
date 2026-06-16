# kotlin-skeleton

Kotlin + Spring Boot 백엔드 스켈레톤. 새 API 프로젝트 시작점.

## Module Layout

This skeleton uses coarse-grained Gradle modules.

```text
apps/
  api                 # executable Spring Boot composition/workbench app

modules/
  platform            # web, errors, trace/logging, shared infrastructure
  auth                # stateless auth, JWT, dev-login, break-glass access
  auth-social         # optional social-login extension for auth
  auth-social-google  # optional Google OAuth provider client
  auth-social-kakao   # optional Kakao OAuth provider client
  auth-social-naver   # optional Naver OAuth provider client
  config-aws-ssm      # optional AWS SSM Parameter Store property loading
  event-kafka         # optional Kafka event publishing adapter
  idempotency          # optional Idempotency-Key support for command endpoints
  notification         # optional notification contracts and local broker
  notification-slack   # optional Slack webhook alerts and exception notices
  notification-sse     # optional server-to-web SSE notification delivery
  notification-websocket # optional STOMP/WebSocket notification delivery
  payment             # optional provider-neutral payment contracts
  payment-toss        # optional Toss payment provider
  payment-stripe      # optional Stripe payment provider
  persistence-jpa      # optional JPA audit timestamp support
  persistence-jdbc     # optional Spring Data JDBC audit timestamp support
  redis-core          # optional Redis connection, templates, key prefixing
  redis-lock          # optional Redis-backed distributed locks
  redis-cache         # optional Redis cache manager defaults
  redis-rate-limit    # optional Redis-backed rate-limit store
  scheduler           # optional annotation-driven scheduler
  storage             # optional storage contracts and file validation
  storage-s3          # optional S3 presigned storage adapter
```

Use modules as capability choices:

- `apps/api` composes the runnable sample application and exercises module wiring. Real services can delete its `/api/v1/examples/*` and `/api/v1/skeleton/*` controllers after choosing their own module set.
- `modules/platform` is the shared foundation for most apps. It also contributes default OpenAPI metadata, standard response/error schemas, web policy defaults, outbound HTTP client scaffolding, and trace header documentation.
- `modules/auth` is included when the app needs authentication. Its default beans are Spring Boot auto-configuration defaults, so an app can replace `AuthAccountRepository`, `SecurityFilterChain`, token service, or filters with its own beans. It also contributes JWT bearer security metadata to OpenAPI.
- `modules/auth-social` is included when the app needs social login. Its default beans are also auto-configuration defaults, so account links, provisioning policy, and the social auth handler can be replaced. It also contributes the social-login endpoint to OpenAPI.
- `modules/auth-social-google`, `modules/auth-social-kakao`, and `modules/auth-social-naver` are optional provider clients. Add only the provider modules an application actually needs.
- `modules/idempotency` is included when command endpoints need `Idempotency-Key` protection. It contributes the `@IdempotentOperation` annotation, request fingerprinting, replay headers, an in-memory default store, and OpenAPI header documentation.
- `modules/crypto` is included when the app needs recoverable AES-GCM text encryption for persisted or transported secrets. It provides key-id envelopes, URL-safe opaque tokens, and opt-in persistence converters; redaction still handles logs and alerts.
- `modules/notification` is included when the app needs server-side notification publishing. `modules/notification-sse`, `modules/notification-websocket`, and `modules/notification-slack` add delivery/alert channels.
- `modules:redis-*`, `modules:storage-*`, `modules:payment-*`, `modules:event-kafka`, and `modules:scheduler` are optional capability bundles. `apps/api` includes them to prove they can coexist, while YAML keeps infrastructure-backed features disabled unless explicitly enabled.
- `modules/persistence-jpa` and `modules/persistence-jdbc` are optional persistence adapters. Both use the same platform audit-time contract while keeping JPA/JDBC annotations and lifecycle behavior inside the selected persistence module.

Fine-grained details such as JWT, password login, OAuth, or dev login live as packages inside their capability modules unless they grow into provider-level integrations.

## Composition Workbench

`apps/api` is the backend module assembly workbench. It intentionally depends on the skeleton capability modules, then uses properties to decide which runtime integrations are active.

- `GET /api/v1/skeleton/modules` returns the current module catalog as a standard list response.
- `GET /api/v1/skeleton/redis/key?value=orders:1` proves `redis-core` key prefixing works without pinging Redis.
- `POST /api/v1/skeleton/storage/validate` proves storage file validation wiring.
- `GET /api/v1/skeleton/storage/public-url?key=images/cat.png` proves the configured storage public URL resolver without requiring a product endpoint.
- `POST /api/v1/skeleton/notifications` publishes a provider-neutral notification event so SSE/Slack/WebSocket delivery modules can subscribe.

These endpoints require auth by default. They are development/workbench affordances, not product APIs.

Default `application.yml` keeps Redis lock, Redis cache, Redis rate-limit, scheduler, S3, provider payment modules, and WebSocket disabled so the sample app can start without external infrastructure. Profile-specific YAML can enable stricter behavior. For example, `application-local.yml`, `application-dev.yml`, and `application-prod.yml` enable `redis-lock` by default, so selecting that profile requires a reachable Redis backend unless overridden for tests.

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

- `TimeProvider` is auto-configured by default and returns UTC `Instant` values truncated to microsecond precision for MySQL `DATETIME(6)`.
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

- Kotlin 2.2 / JDK 21
- Spring Boot 4.0
- Spring Data JDBC + Flyway
- MySQL 8.4
- Spring MVC server + WebClient outbound client
- Gradle (Kotlin DSL)

## 빠른 시작

```bash
# 로컬 DB만 띄우기
docker compose up -d mysql

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
