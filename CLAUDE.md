# kotlin-skeleton — Claude Code 컨텍스트

Kotlin + Spring Boot 백엔드 토이 프로젝트의 공개 출발점.

## Backend Module Layout

- `apps/api` is the only executable Spring Boot application.
- `modules/platform` owns shared web/error/observability code.
- `modules/auth` owns authentication contracts and future login flows.
- `modules/auth-social` owns optional provider-neutral social-login contracts and endpoint routing.
- Keep provider/vendor integrations out of `platform`.

## 패키지 구조 (AI 참조용)

```
apps/api/src/main/kotlin/dev/sumin/skeleton/
├── KotlinSkeletonApplication.kt   # 엔트리 포인트 (수정 거의 없음)
└── api/                           # @RestController + 요청/응답 DTO
    └── HelloController.kt

modules/platform/src/main/kotlin/dev/sumin/skeleton/common/
├── ApiError.kt                    # 표준 에러 응답 포맷
├── ApiResponse.kt                 # 표준 성공 응답 envelope
├── ApplicationException.kt        # 도메인 예외 베이스 클래스
├── GlobalExceptionHandler.kt      # 모든 예외 → ApiError 변환
├── openapi/                       # Swagger/OpenAPI 공통 자동 명세
├── RequestLoggingFilter.kt        # 요청 시작/종료 로그
└── TraceIdFilter.kt               # W3C traceparent → MDC traceId/spanId 심기

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
├── providers/                     # google/kakao/naver provider packages when real clients are added
├── api/                           # social login request/handler classes
└── config/                        # Spring Boot auth-social auto-configuration and route registration
```

**경계 책임:**
- `apps/api` 는 실행 앱 조립과 HTTP 변환만. 비즈니스 로직 금지. 서비스 호출.
- 앱 고유 `domain/`, `infra/`, `config/` 패키지는 필요할 때 `apps/api` 안에 둔다.
- `modules/platform` 은 web/error/observability 공통 기반만 담당한다.
- `modules/auth` 는 인증 계약과 향후 로그인 흐름을 담당한다.
- `modules/auth-social` 은 선택형 소셜 로그인 흐름을 담당한다. provider 구현은 `providers/*` 패키지로 추가하고, 아직 별도 Gradle 모듈로 쪼개지 않는다.
- 새 파일 200줄 넘어가면 분할 신호

## 핵심 컨벤션

- **REST 네임스페이스**: `/api/v1/*` — 컨트롤러에서 `@RequestMapping("/api/v1/...")`
- **응답 포맷**:
  - 성공 단건: `ApiResponse.value(dto)` → `{ value, meta }`
  - 성공 목록: `ApiResponse.list(items)` → `{ values, meta }`
  - 성공 페이지: `ApiResponse.page(items, pagination)` → `{ values, pagination, meta }`
  - 컨트롤러/라우트는 `Any`, raw `Object`, 임의 `Map` 대신 명시적 response DTO를 반환한다.
  - 에러: [ApiError] (RFC 7807 변형 + traceId + timestamp)
- **요청 검증**:
  - 요청 DTO에는 Jakarta Bean Validation constraint 를 붙인다.
  - 컨트롤러 request body 는 `@Valid @RequestBody` 로 받는다.
  - validation 실패는 `400 Validation failed` + `ApiError.errors[]` 로 반환한다.
  - 구조적 규칙은 모듈 내부 custom constraint 로 선언한다. 예: `RequiredLoginIdentifier`
- **도메인 예외**: `class XxxNotFoundException : ApplicationException(...)` 식으로 선언, throw만 하면 표준 응답
- **Swagger/OpenAPI**:
  - 기본 경로: `/api/v1/docs`, `/api/v1/docs/ui`
  - code-first. DTO/반환 타입/auto-configuration 이 명세 원천이다.
  - 반복 명세는 module auto-configuration 이 담당한다. controller마다 공통 `ApiError`, trace header, bearer security 를 손으로 반복하지 않는다.
  - 엔드포인트 의미 설명이 필요할 때만 `@Operation`, 필드 의미가 필요할 때만 `@Schema` 를 추가한다.
- **스키마 변경**: `apps/api/src/main/resources/db/migration/V{n}__{desc}.sql` — Flyway 마이그레이션만
- **로그**: SLF4J 사용. 모든 로그 라인엔 traceId/spanId/parentSpanId 자동 포함 (MDC)
- **인증**: `modules/auth` 기본값은 Spring Boot auto-configuration 으로 제공. 실제 앱에서 `AuthAccountRepository`, `SecurityFilterChain`, `JwtTokenService`, 필터 bean을 정의하면 기본값을 대체할 수 있다.

## traceId 흐름 (디버깅용 핵심)

1. 프론트엔드/에이전트가 작업 전체 `traceId`를 담은 W3C `traceparent` 헤더 부착
2. [TraceIdFilter]가 traceId를 승계하고 현재 요청용 새 spanId 생성
3. MDC `traceId`, `spanId`, `parentSpanId` 키에 주입
4. 모든 로그 라인에 `[traceId=... spanId=... parentSpanId=...]` 프리픽스 출력
5. 응답 헤더 `traceparent`, `X-Trace-Id`, `X-Span-Id` 로 현재 서버 span 반환
6. 성공 응답 `meta.traceId`/`meta.spanId`, 에러 응답 body `traceId`/`spanId` 필드에도 포함
7. **디버깅**: 프론트 콘솔/토스트에 찍힌 traceId 로 서버 로그 `grep` → 전체 플로우, spanId 로 특정 요청 단계 좁혀보기

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
  - Endpoint: `POST /api/v1/auth/social/{provider}/login`
  - Frontend obtains provider authorization code; backend exchanges code through enabled provider
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
  5. 공통 web/error/observability 코드는 `modules/platform` 에 둔다
  6. 인증 계약과 로그인 흐름은 `modules/auth` 에 둔다
  7. 기능 모듈이 endpoint/route 를 자동 등록하면 같은 모듈의 `openapi/` 에 명세 기여도 같이 둔다
  8. DB 스키마 바뀌면 `apps/api/src/main/resources/db/migration/V{n}__.sql`

## 변경 이력

`CHANGELOG.md` 에 기록. 새 기능은 `[Unreleased]` 섹션에 먼저 적고, 릴리스 시 버전 섹션으로 승격.

## 스켈레톤 → 실제 프로젝트 전환 시

이 레포에서 `Use this template` 으로 출발한 뒤엔 업스트림 자동 동기화 안 함. 스켈레톤 업데이트는 CHANGELOG 확인해서 필요한 부분만 수동 반영.
