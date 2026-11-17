# platform

모든 모듈이 기대는 공용 기반이다: `ApiError` 응답 봉투와 전역 예외 처리, trace id · 요청 로깅 필터, CORS · rate limit 정책, 외부 HTTP 클라이언트(`ExternalHttpClient`), `TimeProvider`, 민감값 마스킹, 배포 가드 SPI(`DeployGuard` — [배포](../deploy.md)).
모듈끼리 서로의 타입을 모른 채 만나는 계약도 여기 둔다: `AccountErasureListener`(계정 삭제), `SignUpConsentGate`(가입 동의), **`AuthorDirectory`**(작성자 id → 닉네임 · 꼬리표: `account` 가 기본 구현을 내고, board 같은 모듈이 요청당 한 번 묻는다 — 없으면 이름 없음 · 앱이 같은 타입의 빈으로 범위별 이름을 낼 수 있다. 카드의 `deleted` 는 지워진 프로필 표시) 그 곁에 작성자 신원 고리 두 개: **`AuthorIdentities`**(이 계정이 이 공개 작성자 id 로 써도 되는가 — 글을 쓰기 직전에 묻는다, 빈이 없으면 작성자 id 는 계정 id) · **`AuthorAdoptionListener`**(공개 작성자 id 없이 저장된 옛 행을 계정의 첫 프로필로 옮기는 멱등 고리). 서비스 규칙이 한 입력 필드를 거절할 때는 `FieldValidationException` — Bean Validation 과 같은 `400` + `errors[]` 모양으로 나간다.
벤더 연동은 여기에 두지 않는다. 대부분의 모듈이 `implementation` 으로 이 모듈을 가져오므로 webmvc · webflux · validation · springdoc 이 런타임에 함께 온다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:platform"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.authz`, `skeleton.config.validation`, `skeleton.deploy`, `skeleton.http`, `skeleton.observability.links`, `skeleton.openapi`, `skeleton.redaction`, `skeleton.web` — [docs/config/modules/platform.yml](../config/modules/platform.yml) |
| 기본 동작 | 켜짐. 필터 · 예외 처리 · OpenAPI 는 기본값으로 동작하고, CORS · rate limit 은 설정으로 켠다. |
| 부팅에 필요한 것 | 없음. |
| 요청 로그의 오류 이유 | 4xx · 5xx 로 답한 요청의 종료 줄(`skeleton.debug.request`)은 끝에 `code=<오류 코드> reason=<고정 제목[ fields=<필드 이름>]>` 을 붙인다 (예: `← POST /api/v1/notes 400 (3ms) code=COMMON.IDEMPOTENCY_ERROR reason=Missing Idempotency-Key`). 응답 본문을 못 보는 운영 로그에서 "왜 400 인지" 찾는 단서이고, 코드 · 고정 제목 · 필드 이름만 싣는다(본문 · 헤더 값 · 토큰 · 키 없음). 오류를 직접 쓰는 인터셉터는 `RequestErrorNote.record` 로 같은 칸을 채운다 |
| 교체 지점 | `GlobalExceptionHandler`, `TraceIdFilter`, `RequestLoggingFilter`, `TimeProvider`, `PublicEndpointRegistry`, `RateLimitStore`, `RateLimitKeyResolver`, `ExternalHttpClient`, `ExternalHttpErrorMapper`, `ExternalHttpTraceExtractor`, `SensitiveValueRedactor`, `ObservabilityLinkResolver`, `DeployGuardRunner`, `DeployEnvGuard`, `AuthorDirectory`, `OperatorAccess`, `RolePermissionSource`, `OperatorAuditLog`, `TestAccountFilter` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/api-client` |
| 테스트 | `modules/platform/src/test` |

## 운영자 권한 (`common/authz`)

모든 운영 경로의 판정을 한 곳으로 모으는 계약이다. 모듈은 호출자를 `OperatorRef(accountId, tokenRoles, ip)` 로 풀어 `OperatorAccess.require(ref, "payment.refund")` 한 줄로 묻는다(익명 `null` → 401, 권한 없음 → 403 `COMMON.FORBIDDEN`(지금까지와 같은 코드)). 교체 지점도 `OperatorAccess` 빈 하나다.

- **권한 키**: `<모듈>.<동사>` (`^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$`, 64자 이하). `*` 는 전권으로 `skeleton.authz.super-role` 의 역할 전용이다. 모듈은 `PermissionDeclaration(module, [DeclaredPermission(key, defaultRoles)])` 빈으로 자기 권한을 선언한다 — 모듈을 빼면 권한도 사라진다.
- **역할 → 권한**: `RolePermissionSource`. 기본은 yml(`skeleton.authz.roles`, 맵 역할 → 권한 목록). 주지 않으면 `super-role: ["*"]` 에 선언의 `defaultRoles` 를 더한 매핑(board 가 붙으면 `MODERATOR: [board.moderate, board.manage]`). 주면 그 맵이 통째로 이긴다 — 다른 역할의 `*`, 선언에 없는 키, 키 규칙 위반은 기동 실패. 매핑에 없는 역할은 권한이 없다(오류 아님). `authz-jdbc` 가 DB 로 교체하고 `CachingRolePermissionSource` 로 `cache-ttl`(기본 30초, 0 = 끔)만큼 메모리에 담는다.
- **판정**: 기본 `TokenOperatorAccess` 는 토큰 역할을 매핑에 통과시킨다(회수는 토큰 만료까지). `account` 가 있으면 저장소를 다시 읽는 구현이 대신 선다. 구현은 `AbstractOperatorAccess` 를 확장하면 한 요청 안의 같은 계정은 한 번만 계산한다(요청 사이 캐시 없음).
- **`RoleHolders`**: 역할을 가진 활성 계정 수 계약(`account` 가 구현) — 「가진 계정이 있으면 역할 삭제 불가」를 `authz` 가 `account` 를 모르고 판정하게 한다.
- **`AudienceSource`** (`common/audience`): 공지 대상 계약 — `kind` · `param`(`NONE`·`ROLE`·`WORKSPACE`·`TEXT`) · `validate(param)`(사유 또는 null) · `count(param)` · `page(param, after, limit)`(계정 id 오름차순 키셋, 활성 계정만, 겹침 없음). `account-jdbc` 가 `all` · `role`, `workspace-jdbc` 가 `workspace` 를 내고 앱은 세그먼트를 빈 하나로 더한다. 계약만 있고 이 빈들을 읽는 모듈이 없으면 아무 일도 하지 않는다.
- **`TestAccountFilter`** (`common/account`): **시험 계정**(운영자가 `account` 의 `PUT /api/v1/admin/accounts/{id}/test-account` 로 표시한 계정 — 운영에서 점검 · 시험 결제를 하는 계정)을 집계에서 빼는 자리. `notTestSql(accountColumn)` 이 질의의 `where` 에 `and` 로 붙이는 SQL 조건(계정이 시험 계정이 **아니면** 참, 별칭 `tm`)을 돌려주고, `account-jdbc` 가 표시 표(`account_test_marks`)를 읽는 구현을 낸다 — 계정 표를 모르는 모듈은 이 조건만 붙인다. 구현이 없으면 부르는 쪽이 `TestAccountFilter.NONE`(아무도 안 뺀다)을 쓴다. 이 레포에서 건 곳: 대시보드의 신규 가입 · 가입 묶음(퍼널) · 활성 이용자 · 순매출 · 결제 건수 · 쿠폰 사용 수 · 쿠폰 순위, 쿠폰 현황(상태별 발급 · 사용 수와 할인 합계 `DiscountTotals`), 공지 대상 `all`. 건 규칙: 여러 계정을 합치는 집계에만 건다 — 계정 한 명을 보는 화면 · 운영자의 목록 · 그 계정 자신의 요청 · 장부(주문 · 환불 줄)에는 걸지 않는다. 앱이 새 집계(자기 매출표 · 가입 통계)를 만들면 같은 조건을 붙인다.
- **`OperatorAuditLog`**: 운영 행동 기록 자리. 기본(`NoOpOperatorAuditLog`)은 아무것도 하지 않고 로그도 쓰지 않는다 — 저장하려면 `audit-jdbc` 모듈을 붙이거나(append-only 표 · 운영자 조회 · 보관 기한) 앱이 빈 하나를 둔다. `OperatorAction` 은 누가 · 무엇을 · 대상(id) · 선택 `detail`(문자열 맵) · `result`(기본 `SUCCESS`, 거절 · 실패는 `DENIED` · `FAILED`)를 담고, 접속 IP · 기기 정보는 저장 구현이 지금 요청에서 읽는다.
- **`RouteBaggage`** (`common/routing`): 요청 라우팅(`request-routing`)이 쓰는 `baggage` 의 `route` 멤버(`route=<서비스>:<대상>|…`)의 모양 · 파싱 · MDC(`route`) 복원. 이름만 싣고 주소는 싣지 않는다 — 모듈 없이 `event-kafka` 가 발행 머리에 쓰려고 여기 둔다.
- **옛 키 네 개는 지웠다**: `skeleton.account.admin.role` → `skeleton.authz.super-role`, `skeleton.payment.admin.role` → `skeleton.authz.roles.<역할>: [payment.read, payment.refund, payment.operate]`, `skeleton.legal.admin-role` → `skeleton.authz.roles.<역할>: [legal.read]`, `skeleton.board.moderator-role` → `skeleton.authz.roles.<역할>: [board.moderate, board.manage]`. 값이 하나라도 있으면 기동이 실패하고 메시지가 새 자리를 알려 준다(`LegacyRoleKeysGuard`). 화면은 `GET /auth/me` 의 `permissions` 로 받는다.

- **`DayWindow`** (`common/time`): 「하루」 — `DayWindow.containing(now, zone)` 이 그 시간대의 자정부터 다음 자정 전까지 `[from, to)` 를 돌려준다. 하루 단위 상한(`ads-admob` · `ads-web-rewarded`)이 같은 경계를 쓴다.

## 속도 제한 (`skeleton.web.rate-limit` · 모듈의 한도)

두 층이다. ① **경로 패턴 필터** `skeleton.web.rate-limit`(기본 꺼짐): `path-patterns`(비면 `path-pattern` 하나, 기본 `/api/**`)에 맞는 요청을 클라이언트(IP) 기준으로 센다 — 본문을 읽기 전에, 초과는 `429` + `Retry-After`. **규칙이 하나다**: `capacity` · `window` 가 하나이고 목록의 모든 경로가 한 클라이언트의 **한 카운터**를 나눠 쓴다(읽기 경로와 로그인을 한 목록에 섞지 않는다). ② **모듈의 한도**(가입 · 로그인 · 인증 코드 · 비밀번호 찾기 · 로그인 링크 · 문의 …): 각 모듈이 자기 키(`skeleton.account.login.per-ip` …)로 켜 두고(기본 켜짐), 이쪽은 IP · 주소 · 계정별로 따로 센다. 두 층 모두 `RateLimitStore` 에서 세고, 기본 저장소는 **인스턴스마다 따로인 메모리**다 — Cloud Run 같은 여러 · 0대 인스턴스에서는 한도가 N 배로 느슨해지고 0대로 내려가면 비워진다. `redis-rate-limit` 이 저장소를 Redis 로 바꿔 두 층 모두를 공유시킨다. 민감 경로의 예 · 키 표 · 고치는 길: [Cloud Run 0대 · 여러 인스턴스 「민감 경로 속도 제한」](../cloud-run-scale-to-zero.md). 클라이언트 IP 규칙(`skeleton.web.client-ip.mode`)을 정하지 않으면 키를 호출자가 고를 수 있다([client-ip](../client-ip.md)).

## 대시보드 기여 계약 (`common/dashboard`)

관리자 대시보드(`dashboard` 모듈)가 모으는 카드의 **타입만** 있고 빈은 없다 — `account-jdbc` · `auth-session-jdbc` · `payment-jdbc` 가 `dashboard` 를 컴파일에 끌어오지 않고 카드를 기여하게 하려는 자리다(기여 모듈은 자기 자동 설정을 `@ConditionalOnClass(name = …dashboard…)` 로 등록한다 — `dashboard` 를 안 붙인 앱에는 아무것도 생기지 않는다).

- `DashboardMetric`(숫자 타일: `value(range)` · `series(range, interval)`, `comparable = false` 면 직전 기간 없음) · `DashboardRanking`(순위 탭: `top(range, limit)`) · 퍼널(`DashboardCohortSource` 가 묶음을, `DashboardFunnelStep` 이 단계를, `DashboardFunnel` 이 묶어 앱이 바꾼다).
- 기여는 예외 대신 `DashboardResult(status = OK | NOT_CONNECTED | NO_HISTORY | ERROR, data, freshness)` 로 말한다. 범위 `DashboardRange` 는 `[from, to)` 와 대시보드 시간대다. 사용법과 카드 더하는 법은 `dashboard` 모듈 문서(`dashboard.md`).

자세히: [배포 계약 · 배포 가드](../deploy.md) · [설정 키 설명](../configuration.md) · [클라이언트 IP](../client-ip.md) · [에러 계약](../errors.md) · [외부 HTTP](../external-http.md) · [로깅](../logging.md) · [관측 링크](../observability-links.md) · [OpenAPI](../openapi.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
