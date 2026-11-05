# 에이전트 참조 — 패키지 구조 · traceId · auth · 프론트 통신

`AGENTS.md` 에서 링크되는 배경 문서. 코드를 처음 찾아갈 때 본다.

## 패키지 구조 (AI 참조용)

```
apps/api/src/main/kotlin/dev/sumin/skeleton/app/api/        # 스타터 (새 프로젝트의 출발점)
├── ApiApplication.kt              # 엔트리 포인트 — 루트의 하위 패키지라 모듈 패키지를 스캔하지 않는다
└── HelloController.kt             # 샘플. 앱 컨트롤러/DTO 는 이 패키지 아래에

apps/workbench/src/main/kotlin/dev/sumin/skeleton/app/workbench/   # 데모: 모든 모듈 + 샘플 엔드포인트
├── WorkbenchApplication.kt
└── api/                           # /api/v1/skeleton/**, /api/v1/examples/** 샘플 (react-skeleton 워크벤치 계약)
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
- 보호 프로필(`skeleton.auth.protected-profiles`, 기본 `prod`·`staging`)에서는 안전하지 않은 구성으로 기동이 실패한다 (메시지가 속성 · 빈 이름을 적는다):
  - `skeleton.auth.jwt.secret` 이 비었거나 · 내장 기본값이거나 · 32바이트 미만 (`JwtTokenService` 생성 전에 막는다)
  - 내장 메모리 `AuthAccountRepository`(시드 계정)가 쓰이는 중 — 앱이 자기 `AuthAccountRepository` 빈을 둔다
  - local/dev/test/프로필 없음은 그대로다 (스타터 테스트가 시드 사용자에 기댄다)
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
  - Native apps: `POST /api/v1/auth/social/{provider}/token` takes the SDK token instead (`idToken` or `accessToken`, per `nativeToken` in `GET /auth/methods`)
  - Add only the provider module the app needs; `apps/api` (the starter) carries none, only `apps/workbench` carries all
  - `provider + providerUserId` maps to internal `accountId`
  - JWT response shape is the same as password login
  - roles are always loaded from `AuthAccountRepository`
  - default route registration is provided by auth-social auto-configuration, not component scanning
- YAML 은 얇게 유지한다. `skeleton.auth.jwt.secret`, `skeleton.auth.break-glass.secret` 같은 secret 은 환경변수로 주입한다.

## 프론트엔드와의 통신

프론트(React + Vite, [react-skeleton](https://github.com/turong92/react-skeleton))가 같은 도메인에서 `/api/v1/*` 호출. 프로덕션에선 Caddy가 정적 번들 + 백엔드 프록시를 한 origin으로 합침 → **CORS 불필요**.
