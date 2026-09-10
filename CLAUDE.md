# kotlin-skeleton — Claude Code 컨텍스트

Kotlin + Spring Boot 백엔드 토이 프로젝트의 공개 출발점.

## Backend Module Layout

- `apps/api` is the only executable Spring Boot application.
- `modules/platform` owns shared web/error/observability code.
- `modules/auth` owns authentication contracts and future login flows.
- `modules/auth-social` owns optional provider-neutral social-login contracts and endpoint routing.
- `modules/auth-social-google`, `modules/auth-social-kakao`, and `modules/auth-social-naver` own optional provider-specific OAuth HTTP clients.
- `modules/idempotency` owns optional `Idempotency-Key` command endpoint protection.
- `modules/crypto` owns optional AES-GCM text encryption and opt-in persistence converters.
- `modules/notification` owns provider-neutral notification contracts and the local broker default.
- `modules/notification-sse` owns optional Spring MVC server-sent event delivery.
- `modules/persistence-jpa` owns optional JPA audit timestamp mapping and lifecycle callbacks.
- `modules/persistence-jdbc` owns optional Spring Data JDBC audit timestamp mapping and callbacks, plus DB-session UTC and JVM-zone-independent `Instant`/`LocalDate`/`LocalDateTime` conversions.
- `modules/persistence-jooq` owns the jOOQ variant: DDL-file code generation (`DDLDatabase`), `UtcInstantConverter` for `*_at` columns, `JooqAuditRecordListener`.
- `modules/job-queue-jdbc` owns the MySQL retry queue (`skeleton_jobs`, `JobQueue`/`JobHandler`, `FOR UPDATE SKIP LOCKED`, backoff, DEAD). Handlers must be idempotent.
- `modules/notification-mail` owns SMTP sending (`MailSender`), off unless `skeleton.notification-mail.enabled` and `spring.mail.host` are set.
- `modules/captcha-turnstile` owns Cloudflare Turnstile verification (`TurnstileVerifier`), off unless enabled.
- Schema management modes (Flyway or `schema.sql` via `spring.sql.init`) are documented in `docs/schema-management.md`; module tables ship Flyway files with date versions (`V2026MMDDnn__`).
- New project from the skeleton: `scripts/rename-skeleton.sh <package> <prefix> <ClassPrefix>` then `./gradlew build`; module picking in `docs/minimal-composition.md`.
- `modules/time` owns viewer time zone/locale resolution, `ZonedMoment` (scheduled local time), human-readable dual formatting, and country → time zone lookup. Three temporal kinds: `Instant` (facts), `LocalDate` (calendar dates, never converted), `ZonedMoment` (future local times). Never use `ZoneId.systemDefault()`, `TIMESTAMP` columns, or bare `LocalDateTime` for instants.
- Keep provider/vendor integrations out of `platform`.

## 패키지 구조 (AI 참조용)

```
apps/api/src/main/kotlin/dev/sumin/skeleton/
├── KotlinSkeletonApplication.kt   # 엔트리 포인트 (수정 거의 없음)
└── api/                           # @RestController + 요청/응답 DTO
    ├── HelloController.kt
    └── OperationExampleController.kt # REST operation contract 샘플

modules/platform/src/main/kotlin/dev/sumin/skeleton/common/
├── audit/                         # persistence-neutral audit timestamp contract
├── ApiError.kt                    # 표준 에러 응답 포맷
├── ApiResponse.kt                 # Response.ok/created/accepted/noContent + 표준 성공 응답 envelope
├── ApiResponseEntity.kt           # old compatibility delegate
├── ApplicationException.kt        # 도메인 예외 베이스 클래스
├── GlobalExceptionHandler.kt      # 모든 예외 → ApiError 변환
├── http/                          # outbound WebClient facade, timeout/error mapping
├── PageQuery.kt                   # 표준 page/size query contract
├── openapi/                       # Swagger/OpenAPI 공통 자동 명세
├── RequestLoggingFilter.kt        # 요청 시작/종료 로그
├── time/                          # UTC Instant provider + DB precision policy
├── TraceIdFilter.kt               # W3C traceparent → MDC traceId/spanId 심기
└── web/                           # forwarded headers, public endpoints, CORS, rate limit

modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/
├── account/                       # account lookup abstraction and default seed repository
├── api/AuthController.kt          # password login and current-user endpoint
├── config/AuthAutoConfiguration.kt # overridable Spring Boot auth defaults
├── jwt/JwtTokenService.kt         # HS256 JWT issue/authenticate
├── openapi/                       # auth capability OpenAPI 기여
├── principal/CurrentPrincipal.kt  # 인증 주체 계약
└── security/                      # JWT, dev-login, break-glass filters

modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/
├── oauth/                         # provider-neutral social-login contracts and service
├── openapi/                       # auth-social capability OpenAPI 기여
├── api/                           # social login request/handler classes
└── config/                        # Spring Boot auth-social auto-configuration and route registration

modules/auth-social-google/src/main/kotlin/dev/sumin/skeleton/auth/social/google/
└── GoogleOAuthProvider.kt         # optional Google authorization-code client

modules/auth-social-kakao/src/main/kotlin/dev/sumin/skeleton/auth/social/kakao/
└── KakaoOAuthProvider.kt          # optional Kakao authorization-code client

modules/auth-social-naver/src/main/kotlin/dev/sumin/skeleton/auth/social/naver/
└── NaverOAuthProvider.kt          # optional Naver authorization-code client

modules/idempotency/src/main/kotlin/dev/sumin/skeleton/idempotency/
├── IdempotentOperation.kt         # opt-in command endpoint annotation
├── IdempotencyStore.kt            # storage/replay contract
├── InMemoryIdempotencyStore.kt    # replaceable default store
├── IdempotencyHandlerInterceptor.kt
├── IdempotencyCachingFilter.kt
└── IdempotencyAutoConfiguration.kt

modules/notification/src/main/kotlin/dev/sumin/skeleton/notification/
├── NotificationEvent.kt           # notification DTO and severity
├── NotificationContracts.kt       # publisher/subscription contracts
├── InMemoryNotificationBroker.kt  # single-node skeleton broker
└── NotificationAutoConfiguration.kt

modules/notification-sse/src/main/kotlin/dev/sumin/skeleton/notification/sse/
├── NotificationSseController.kt   # GET /api/v1/notifications/sse
├── NotificationSseService.kt      # SseEmitter lifecycle and fanout
├── NotificationSseProperties.kt
└── NotificationSseAutoConfiguration.kt

modules/persistence-jpa/src/main/kotlin/dev/sumin/skeleton/persistence/jpa/
├── AuditTimestamps.kt             # JPA @Embeddable audit fields
└── BaseJpaEntity.kt               # optional @MappedSuperclass convenience

modules/persistence-jdbc/src/main/kotlin/dev/sumin/skeleton/persistence/jdbc/
├── AuditTimestamps.kt             # JDBC immutable audit value object
├── JdbcAuditable.kt               # opt-in contract for audit callbacks
├── JdbcAuditBeforeConvertCallback.kt
└── JdbcAuditAutoConfiguration.kt
```

**경계 책임:**
- `apps/api` 는 실행 앱 조립과 HTTP 변환만. 비즈니스 로직 금지. 서비스 호출.
- 앱 고유 `domain/`, `infra/`, `config/` 패키지는 필요할 때 `apps/api` 안에 둔다.
- `modules/platform` 은 web/error/observability 공통 기반만 담당한다.
- `modules/auth` 는 인증 계약과 향후 로그인 흐름을 담당한다.
- `modules/auth-social` 은 선택형 소셜 로그인 공통 흐름을 담당한다. 실제 provider 구현은 `modules/auth-social-google|kakao|naver` 같은 선택 Gradle 모듈로 둔다.
- `modules/idempotency` 는 중복 실행 방지가 필요한 command endpoint 만 담당한다. 저장소는 `IdempotencyStore` 로 교체한다.
- `modules/notification` 은 알림 이벤트 계약과 기본 로컬 브로커를 담당한다. `modules/notification-sse` 는 웹 클라이언트 SSE 전달만 담당한다.
- `modules/platform` 은 `TimeProvider`, `BaseAuditTimestamps` 같은 persistence-neutral 시간 계약만 둔다.
- `modules/persistence-jpa|jdbc` 는 같은 audit 계약을 각 persistence annotation/callback 방식으로 구현한다.
- 새 파일 200줄 넘어가면 분할 신호

## 핵심 컨벤션

- **REST 네임스페이스**: `/api/v1/*` — 컨트롤러에서 `@RequestMapping("/api/v1/...")`
- **응답 포맷**:
  - 값 없음: `Response.ok()` → `{ meta }`
  - 성공 단건: `Response.ok(dto)` → `{ value, meta }`
  - 성공 목록: `Response.ok(items)` → `{ values, meta }`
  - 성공 페이지: `Response.ok(items, pagination)` → `{ values, pagination, meta }`
  - 커서 목록: `Response.ok(items, hasNext) { it.id }` → `{ values, cursor, meta }`
  - 생성: `@CreatedOperation` + `Response.created(location, dto)` → `201 Created` + `Location`
  - 비동기 시작: `@AcceptedOperation` + `Response.accepted(dto)` → `202 Accepted`
  - 삭제/토글/명령 완료: `@NoContentOperation` + `Response.noContent()` → `204 No Content`
  - 컨트롤러/라우트는 `Any`, raw `Object`, 임의 `Map` 대신 명시적 response DTO를 반환한다.
  - 에러: [ApiError] (RFC 7807 변형 + traceId + timestamp)
- **요청 검증**:
  - 요청 DTO에는 Jakarta Bean Validation constraint 를 붙인다.
  - 컨트롤러 request body 는 `@Valid @RequestBody` 로 받는다.
  - 페이지 조회는 `@Valid @ParameterObject @ModelAttribute PageQuery` 를 기본으로 쓰며, `page>=0`, `1<=size<=100` 을 표준으로 한다.
  - validation 실패는 `400 Validation failed` + `ApiError.errors[]` 로 반환한다.
  - 구조적 규칙은 모듈 내부 custom constraint 로 선언한다. 예: `RequiredLoginIdentifier`
- **도메인 예외**: `class XxxNotFoundException : ApplicationException(...)` 식으로 선언, throw만 하면 표준 응답
- **Swagger/OpenAPI**:
  - 기본 경로: `/api/v1/docs`, `/api/v1/docs/ui`
  - code-first. DTO/반환 타입/auto-configuration 이 명세 원천이다.
  - 반복 명세는 module auto-configuration 이 담당한다. controller마다 공통 `ApiError`, trace header, bearer security 를 손으로 반복하지 않는다.
  - 생성/비동기/204 명세는 `@CreatedOperation`, `@AcceptedOperation`, `@NoContentOperation` 으로 표준화한다.
  - 중복 실행 방지가 필요한 command endpoint 는 `@IdempotentOperation` 을 붙인다. OpenAPI 에 required `Idempotency-Key` header 와 `409` 응답이 자동 추가된다.
  - 엔드포인트 의미 설명이 필요할 때만 `@Operation`, 필드 의미가 필요할 때만 `@Schema` 를 추가한다.
- **스키마 변경**: `apps/api/src/main/resources/db/migration/V{n}__{desc}.sql` — Flyway 마이그레이션만
- **시간/DB timestamp**:
  - 일반 timestamp 는 Kotlin `Instant`, DB `DATETIME(6)` UTC, API ISO-8601 `...Z` 를 표준으로 한다.
  - `TimeProvider` 는 기본 auto-configuration 으로 제공되며 MySQL `DATETIME(6)` 에 맞게 microsecond precision 으로 truncate 한다.
  - 공통 계약은 `BaseAuditTimestamps`; JPA/JDBC 구현체 이름은 각 모듈 안에서 `AuditTimestamps` 로 둔다.
  - JPA 앱은 `modules/persistence-jpa` 의 `AuditTimestamps` 또는 `BaseJpaEntity` 를 사용한다.
  - JDBC 앱은 `modules/persistence-jdbc` 의 `AuditTimestamps` 와 `JdbcAuditable` 을 사용한다.
  - DTO는 기본적으로 `createdAt`, `updatedAt`, `deletedAt` flat 필드로 변환한다.
- **로그**: SLF4J 사용. 모든 로그 라인엔 traceId/spanId/parentSpanId 자동 포함 (MDC)
- **Web policy**:
  - public open 은 `PublicEndpointContributor` 로 추가한다. auth 기본 security chain 이 registry 를 읽어 `permitAll` 을 적용한다.
  - forwarded headers/security headers 는 platform 기본값을 사용한다.
  - CORS/rate-limit 은 scaffold 만 있고 기본 OFF. 필요할 때 `skeleton.web.cors.enabled=true`, `skeleton.web.rate-limit.enabled=true` 로 켠다.
- **외부 HTTP**:
  - raw `WebClient` 직접 생성보다 `ExternalHttpClient` 를 우선 사용한다.
  - `GET`/`POST`/`PUT`/`PATCH`/`DELETE` helper 로 호출하고, 각 호출에서 header/query/body/timeout/error mapper 를 조작한다.
  - 서버는 Spring MVC 를 유지한다. WebFlux 는 outbound WebClient runtime 용으로만 사용한다.
- **Idempotency**:
  - Optional module: `modules/idempotency`
  - `@IdempotentOperation` 은 `Idempotency-Key` 필수 command endpoint 를 뜻한다. optional mode 는 만들지 않는다.
  - missing key → `400 ApiError`, same key + different request fingerprint → `409 ApiError`, in-progress duplicate → `409 ApiError`
  - same key + same method/path/query/body fingerprint 는 첫 응답 status/body/Location 을 replay 한다.
  - 기본 저장소는 in-memory skeleton 용이다. 운영에서는 Redis/JDBC 등으로 `IdempotencyStore` bean 을 교체한다.
- **Notification**:
  - 서버 내부 알림 발행은 `NotificationPublisher` 를 사용한다.
  - 웹 전달이 필요할 때만 `modules/notification-sse` 를 앱에 추가한다.
  - SSE endpoint 는 `/api/v1/notifications/sse`, 기본은 인증 필요. public open 이 필요할 때만 `skeleton.notification.sse.public-endpoint=true`.
- **인증**: `modules/auth` 기본값은 Spring Boot auto-configuration 으로 제공. 실제 앱에서 `AuthAccountRepository`, `SecurityFilterChain`, `JwtTokenService`, 필터 bean을 정의하면 기본값을 대체할 수 있다.

## traceId 흐름 (디버깅용 핵심)

1. 프론트엔드/에이전트가 작업 전체 `traceId`를 담은 W3C `traceparent` 헤더 부착
2. [TraceIdFilter]가 traceId를 승계하고 현재 요청용 새 spanId 생성
3. MDC `traceId`, `spanId`, `parentSpanId` 키에 주입
4. 모든 로그 라인에 `[traceId=... spanId=... parentSpanId=...]` 프리픽스 출력
5. 응답 헤더 `traceparent`, `X-Trace-Id`, `X-Span-Id` 로 현재 서버 span 반환
6. 성공 응답 `meta.traceId`/`meta.spanId`, 에러 응답 body `traceId`/`spanId` 필드에도 포함
7. **디버깅**: 프론트 콘솔/토스트에 찍힌 traceId 로 서버 로그 `grep` → 전체 플로우, spanId 로 특정 요청 단계 좁혀보기
8. 외부 HTTP 호출은 `ExternalHttpClient` 가 `traceparent`/`X-Trace-Id` 를 전파한다.

## auth 흐름 (스켈레톤 기본값)

- `POST /api/v1/auth/login`: `accountId`, `username`, 또는 `email` + `password` 로 로그인하고 bearer JWT 발급
- `GET /api/v1/auth/me`: 현재 `CurrentPrincipal` 반환
- 기본 개발 계정:
  - `acc_user` / `user` / `user@example.com` / `password` / `USER`
  - `acc_admin` / `admin` / `admin@example.com` / `password` / `USER,ADMIN`
- JWT claim: `sub=accountId`, `username`, `email`, `roles`, `iss`, `iat`, `exp`
- dev login:
  - `skeleton.auth.dev-login.enabled=true` + `local`/`dev` profile 에서만 동작
  - `X-Dev-Account-Id`, `X-Dev-Username`, `X-Dev-Email` 로 실제 계정 선택
  - roles 관련 헤더는 읽지 않는다. 권한은 항상 `AuthAccountRepository` 기준
- break-glass:
  - `skeleton.auth.break-glass.enabled=true`
  - `X-Break-Glass-Secret`, `X-Break-Glass-Reason`, `X-Break-Glass-Account-Id` 필요
  - `prod`/`staging` 에서는 secret + allowed account ids 없으면 startup validation 실패
  - secret 은 로그에 남기지 않는다. reason/account 는 감사 로그로 남긴다.
- social login:
  - Optional module: `modules/auth-social`
  - Optional provider modules: `modules/auth-social-google`, `modules/auth-social-kakao`, `modules/auth-social-naver`
  - Endpoint: `POST /api/v1/auth/social/{provider}/login`
  - Frontend obtains provider authorization code; backend exchanges code through enabled provider
  - Add only the provider module the app needs; `apps/api` does not have to carry all providers
  - `provider + providerUserId` maps to internal `accountId`
  - JWT response shape is the same as password login
  - roles are always loaded from `AuthAccountRepository`
  - default route registration is provided by auth-social auto-configuration, not component scanning
- YAML 은 얇게 유지한다. `skeleton.auth.jwt.secret`, `skeleton.auth.break-glass.secret` 같은 secret 은 환경변수로 주입한다.

## 프론트엔드와의 통신

프론트(React + Vite, [react-skeleton](https://github.com/turong92/react-skeleton))가 같은 도메인에서 `/api/v1/*` 호출. 프로덕션에선 Caddy가 정적 번들 + 백엔드 프록시를 한 origin으로 합침 → **CORS 불필요**.

## 작업 원칙

- **Kotlin idiomatic**: data class, scope function (`let`/`apply`/`also`), null 안전성 활용
- **테스트**: Testcontainers로 실 MySQL 띄워 Flyway 마이그레이션 포함 검증
- **새 기능 추가 시**:
  1. `apps/api` 에 컨트롤러 + DTO
  2. 앱 고유 비즈니스 로직은 `apps/api` 안의 `domain/` 패키지에 둔다 (필요 시)
  3. 앱 고유 DB/외부 연동은 `apps/api` 안의 `infra/` 패키지에 둔다 (필요 시)
  4. 요청 DTO에 Bean Validation constraint 를 붙이고 integration test 로 `ApiError.errors[]` 를 확인한다
  5. 생성/비동기/204/page 응답은 platform 표준 helper와 annotation 을 먼저 사용한다
  6. 공통 web/error/observability 코드는 `modules/platform` 에 둔다
  7. 인증 계약과 로그인 흐름은 `modules/auth` 에 둔다
  8. 외부 API 연동은 `ExternalHttpClient` 기반으로 만들고, provider/vendor 별 mapper/customizer 만 추가한다
  9. 기능 모듈이 endpoint/route 를 자동 등록하면 같은 모듈의 `openapi/` 에 명세 기여도 같이 둔다
  10. DB 스키마 바뀌면 `apps/api/src/main/resources/db/migration/V{n}__.sql`
  11. entity audit 이 필요하면 선택한 persistence 모듈의 `AuditTimestamps` 를 사용하고, core/platform 에 JPA/JDBC annotation 을 직접 추가하지 않는다.

## 변경 이력

`CHANGELOG.md` 에 기록. 새 기능은 `[Unreleased]` 섹션에 먼저 적고, 릴리스 시 버전 섹션으로 승격.

## 스켈레톤 → 실제 프로젝트 전환 시

이 레포에서 `Use this template` 으로 출발한 뒤엔 업스트림 자동 동기화 안 함. 스켈레톤 업데이트는 CHANGELOG 확인해서 필요한 부분만 수동 반영.
