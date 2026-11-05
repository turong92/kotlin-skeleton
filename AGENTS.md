# kotlin-skeleton — 에이전트 지침

Kotlin + Spring Boot 백엔드 토이 프로젝트의 공개 출발점.

폴더별 규칙은 그 폴더의 `AGENTS.md` 에 있다: `modules/AGENTS.md`(모듈이 소유하는 것) · `apps/AGENTS.md`(실행 앱 · 새 기능 추가 순서 · 샘플 앱). 패키지 구조 · traceId 흐름 · auth 흐름 · 프론트 통신은 `docs/agent-reference.md`.

## 빌드 · 테스트

- 전체 빌드: `./gradlew build` (Docker/Testcontainers 필요). 바뀐 모듈만: `./gradlew :modules:<module>:test`. 문서 · 카탈로그 시험은 `:modules:platform:test`.
- 찍기(new-project) 시험: `bash scripts/test-new-project.sh --quick` (`--full` 은 조합별 빌드, 수 분).
- 카탈로그 검사: `perl scripts/build-capabilities.pl --check`. 마이그레이션 잠금 검사: `perl scripts/migrations-lock.pl --check`.

## 기능 카탈로그 — 만들기 전에 먼저 읽는다

- **무엇이든 새로 만들기 전에, 그리고 새 프로젝트를 세팅하기 전에 `llms.txt`(결정표 · 기능 한눈에)를 읽는다.** 필요하면 `capabilities.json`(정본) · `docs/capabilities.md`(결정표 · 항목 상세). 이미 준비된 모듈 · 앱 · 스크립트를 다시 만들지 않고, 필요한 모듈과 `new-project.sh` 조각을 거기서 고르기 위해서다. 새 프로젝트의 순서(제품 → 모듈 → 명령 → 설정 · 비밀 → `scripts/dev.sh` → 검증 → `deploy/app.yaml`, 작업 예 3개): 스켈레톤 레포의 `docs/new-project-recipe.md`(찍은 프로젝트에는 없다). 짝 프런트의 같은 문서는 형제 레포 react-skeleton.
- `docs/capabilities.md` · `llms.txt` 는 `perl scripts/build-capabilities.pl`(`--check` 는 쓰지 않고 검사)이 `capabilities.json` 에서 만든다 — 손으로 고치지 않는다. 항목 모양: `docs/capabilities.schema.json`.
- 모듈 · 앱 · 스크립트를 더하거나 지우면 `CapabilitiesCatalogTest`(`modules/platform`)가 실패하며 **붙일 객체를 그대로 보여 준다** → `capabilities.json` 의 `capabilities` 에 붙이고 `TODO`(요약 · 키워드 · 쓰지 않는 경우 · 비밀 · 짝 프런트)를 채운 뒤 `perl scripts/build-capabilities.pl`. 가드가 맞춰 보는 것: 항목 ↔ 모듈 · 앱, `newProjectFlag`(`new-project.sh --dry-run`)와 `autoIncludes`(Gradle 의존 닫힘), `config.prefixes`(`@ConfigurationProperties`), `basePaths`(컨트롤러), 문서 경로와 모듈 문서의 「프론트 짝」, `secrets`(docs/deploy.md §7), 레시피의 명령, 형제 레포가 옆에 있으면 패키지 · 항목 id.
- `scripts/new-project.sh` 는 찍은 프로젝트의 `capabilities.json` 을 고른 모듈로 걸러 다시 쓰고(빠진 것은 `stampedFrom.omitted` 로 스켈레톤을 가리킨다) `llms.txt` · `docs/capabilities.md` 를 새 이름으로 다시 만든다.

## 모듈 · 앱 공통 규칙

- Modules are never reached through component scan. A module registers every bean through its `AutoConfiguration` (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`); no `@Component`/`@Service`/`@Configuration` on module `main` classes. Only the MVC handler markers (`@RestController`, `@RestControllerAdvice`) remain — Spring MVC finds handlers only through them — and each is registered by an autoconfiguration `@Bean` with `@ConditionalOnMissingBean`. `ModuleRegistrationRulesTest` (platform) enforces both rules over every `modules/*/src/main`.
- Every module has a one-page doc `docs/modules/<module>.md` (fixed template; index `docs/modules/README.md`). `ModuleDocumentationTest` (platform) fails when a module has no page, a page names a prefix / override type / dependency / path the code does not have, or the index drifts. Add or change a module → update its page. `scripts/new-project.sh` deletes the pages and index rows of modules it prunes.
- A module may depend on another module only when it uses its types: `api(...)` when those types appear in its public API, `implementation(...)` otherwise, `compileOnly(...)` for an optional integration (`storage-s3` → `crypto`: only the OPAQUE public URL needs it; registered by its own `@ConditionalOnClass` autoconfiguration, covered by `src/noCryptoTest` with `crypto` really off the classpath). Never `testImplementation(project(...))` of a sibling — a test dependency on another module would be dragged into stamped projects.
- Skeleton modules provide mechanisms, not usage choices: they do not change framework defaults (e.g. Flyway `out-of-order`). Choices are explicit overrides in the app's `application.yml` (`apps/api` and `apps/workbench` set `spring.flyway.locations`/`out-of-order`/`validate-migration-naming`). 스켈레톤 모듈은 Flyway 기본값을 바꾸지 않는다.
- **Migrations are frozen once they are in `migrations.lock`** (path + sha256 of every shipped Flyway file): never edit or delete a locked file — add a new `V` file (`./gradlew newMigration -Pname=…`) and lock it with `perl scripts/migrations-lock.pl`. `MigrationLockTest` / CI run `--check`; a changed hash needs an explicit `--rewrite <path>` plus a CHANGELOG line. The sample app's upgrade tests (`MigrationUpgradeIntegrationTest`, `MySqlMigrationUpgradeTest`) migrate baseline-schema data to the latest on both databases. The `local` profile does not wipe the DB any more (`clean-on-validation-error: false`; to throw a local DB away, `docker compose down -v`). Details: `docs/schema-management.md`.
- New project from the skeleton: `scripts/new-project.sh <target> <package> <prefix> <ClassPrefix> [--modules a,b] [--db postgresql|mysql]` (copy, pick modules closed over `project(":modules:x")` deps, rename, then `./gradlew build`); the rules and a per-module "requires to boot" table are in `docs/minimal-composition.md`. Rename only: `scripts/rename-skeleton.sh`.
- Deploy: the app is one image + one declaration (`deploy/app.yaml`), and the homeserver platform generates compose / Caddy / tunnel / backup — never add production compose, Caddyfile, cloudflared or backup files here. Contract (local image build — GHCR is not used, the image workflow is manual-only/on hold), platform-injected env (`<ENV_PREFIX>_REDIS_*`, `_WEB_CLIENT_IP_*`), the required `secrets:` list (random vs operator-provided via `data/secrets/<app>.local.env`), the honest `/health` and the smoke-deploy recipe: `docs/deploy.md`; proven by `scripts/test-deploy-contract.sh` (real containers; `MARINA_DIRECT=1` under marina).
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
- **스키마 변경**: `./gradlew newMigration -Pname=<snake_case>` → `apps/api/src/main/resources/db/migration/postgresql/V<UTC 14자리>__<name>.sql`. 공유 환경에 나간 파일은 고치지 않는다 (docs/schema-management.md)
- **시간/DB timestamp**:
  - 일반 timestamp 는 Kotlin `Instant`, DB PG `timestamptz` (MySQL `datetime(6)` UTC), API ISO-8601 `...Z` 를 표준으로 한다. JdbcClient 시각 파라미터는 `SqlDialect.instantParam` / `readInstant` 로만.
  - `TimeProvider` 는 기본 auto-configuration 으로 제공되며 DB 마이크로초(PG `timestamptz`, MySQL `datetime(6)`)에 맞게 microsecond precision 으로 truncate 한다.
  - 공통 계약은 `BaseAuditTimestamps`; JPA/JDBC 구현체 이름은 각 모듈 안에서 `AuditTimestamps` 로 둔다.
  - JPA 앱은 `modules/persistence-jpa` 의 `AuditTimestamps` 또는 `BaseJpaEntity` 를 사용한다.
  - JDBC 앱은 `modules/persistence-jdbc` 의 `AuditTimestamps` 와 `JdbcAuditable` 을 사용한다.
  - DTO는 기본적으로 `createdAt`, `updatedAt`, `deletedAt` flat 필드로 변환한다.
- **로그**: SLF4J 사용. 모든 로그 라인엔 traceId/spanId/parentSpanId 자동 포함 (MDC). 흐름: `docs/agent-reference.md`
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
- **계정**: 가입 · 이메일 확인 · 재설정 · 세션 · 삭제는 `modules/account`·`auth-session` (스타터에 들어 있다). 시드 계정은 `skeleton.account.seed.accounts` — **로컬 프로필과 시험에서만** (stage · prod 에서는 기동 실패). 비밀번호 · 토큰을 담는 요청/응답 DTO 는 `toString` 을 가린다 (Spring MVC 가 DEBUG 에서 본문을 찍는다). 자세한 것: `docs/accounts.md`.
- **인증**: `modules/auth` 기본값은 Spring Boot auto-configuration 으로 제공. 실제 앱에서 `AuthAccountRepository`, `SecurityFilterChain`, `JwtTokenService`, 필터 bean을 정의하면 기본값을 대체할 수 있다. 기본 계정 · JWT claim · dev login · break-glass · social login 흐름과 보호 프로필 규칙: `docs/agent-reference.md`.

## 작업 원칙

<!-- skeleton-only:start -->

**확장 지점**: 이 레포는 뼈대다 — 확장 지점은 설계 대상이다. 단 새 확장 지점은 실제로 갈아 끼울 두 번째 구현이 계획에 있을 때만 둔다(하나뿐이면 얇게 두고, 두 번째가 계획에 들어올 때 넓힌다).

<!-- skeleton-only:end -->

- **Kotlin idiomatic**: data class, scope function (`let`/`apply`/`also`), null 안전성 활용
- **테스트**: Testcontainers로 실 PostgreSQL(`postgres:18`) 띄워 Flyway 마이그레이션 포함 검증. 방언을 타는 모듈은 MySQL 묶음도 돈다. **컨테이너는 JVM 당 하나(static) + 컨텍스트마다 새 데이터베이스** — 테스트 설정에 `@Bean @ServiceConnection` 컨테이너를 두지 않는다(`TestContainerRulesTest` 가 막는다, 이유 · 정본: `docs/testing.md`)

## 변경 이력

`CHANGELOG.md` 에 기록. 새 기능은 `[Unreleased]` 섹션에 먼저 적고, 릴리스 시 버전 섹션으로 승격.

## 스켈레톤 → 실제 프로젝트 전환 시

이 레포에서 `Use this template` 으로 출발한 뒤엔 업스트림 자동 동기화 안 함. 스켈레톤 업데이트는 CHANGELOG 확인해서 필요한 부분만 수동 반영.
