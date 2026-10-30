# api-key

API 키 — B2B 호출자가 쓰는 장기 자격. 사용자가 **발급**(이름 · 범위 · 만료)하고, 내 목록에서 보고, **회수**하며, 키로 온 요청은 **표시한 경로에 한해** 주인 계정으로 움직인다.
저장은 어댑터 `api-key-jdbc` 가 한다(메모리 구현 없음 — 없으면 기동에 실패한다). 운영자는 전체 목록을 보고 사유를 적어 회수할 수 있다(`admin.enabled`, 모듈 기본 꺼짐).

**토큰** `<접두사>_<base62 32자>`(≈190비트, 모듈 기본 접두사 `apk` — 앱이 바꿔 비밀 검출 규칙에 건다). 저장은 SHA-256 해시뿐이고(높은 엔트로피라 느린 해시가 필요 없다 — OWASP), 토큰 원문은 **발급 응답에만** 있다(`Cache-Control: no-store`). 화면에는 힌트 `접두사…끝 4자` 만 보인다.
**요청 인증** `Authorization: Bearer <키>` — `auth` 의 `BearerTokenAuthenticator` 고리에 꽂힌다(`JwtAuthenticationFilter` 가 JWT 로 읽기 전에 묻고, 접두사로 가른다). 거절은 모두 JSON: 모르는 키 401 `API_KEY.INVALID` · 만료 401 `API_KEY.EXPIRED` · 회수 401 `API_KEY.REVOKED`(키를 가진 사람에게만 보이므로 이유를 알려 준다) · 주인이 막힘(정지 · 탈퇴 대기 · 없어짐) 403 `API_KEY.OWNER_BLOCKED`.
통과하면 주체는 **주인 계정에 역할을 비운 것**이다 — `Authentication.name` 을 쓰는 기존 컨트롤러가 그대로 돌고, 역할이 없어 운영 경로(`OperatorAccess`)는 닿지 않는다. `last_used_at` · IP 는 1분보다 오래됐을 때만 조건부 UPDATE 로 쓴다(요청이 몰려도 쓰기는 분당 한 번).

**키로 닿는 곳 — 기본 거부**(`ApiKeyAccessInterceptor`, 다른 문보다 먼저): 키로 온 요청은 처리기(메서드 또는 컨트롤러 클래스)에 `@ApiKeyScope("notes.read")`(`platform`) 표시가 있어야 하고(없으면 403 `API_KEY.NOT_ALLOWED` — 정적 자원 · 함수형 라우트도 같다), 키가 그 범위를 가져야 한다(403 `API_KEY.SCOPE_MISSING`, `data.scope`).
범위는 앱 · 모듈이 `ApiKeyScopeDeclaration(module, scopes)` 빈으로 **선언**한다(`api-key` 에 의존하지 않고 선언만 — `PermissionDeclaration` 처럼, `api-key` 가 없으면 아무 일도 안 한다). 범위 키 모양은 권한 키와 같고(`notes.read`) 운영 권한과는 다른 이름 공간이다. 이메일 필수 · 재동의 같은 사람용 문은 키에도 그대로 걸린다(주인이 풀어야 한다).
키별 한도(`rate-limit.capacity > 0` 일 때)는 키로 온 모든 요청을 센다 — `RateLimitStore` 키 `api-key:<id>`(`redis-rate-limit` 이 있으면 Redis). 넘으면 429 `API_KEY.RATE_LIMITED` + `Retry-After` + `X-RateLimit-*`.

**사용자 경로**(`skeleton.api-key.http.enabled`, 붙이면 켜짐; 키로는 닿지 않는다 — 표시가 없으므로 키로 키를 만들 수 없다):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/api-keys` | 내 키 `[{id, name, hint, scopes, status ACTIVE·EXPIRED·REVOKED, revokedBy USER·OPERATOR, createdAt, expiresAt, lastUsedAt}]` — 토큰 · 해시 · 운영자 사유는 없다 |
| `POST /api/v1/api-keys {name, scopes[], expiresAt?, 다시 인증}` | 발급 → `201 {key, token}`. **다시 인증**(`account` 의 `Reauth` — 비밀번호 · 메일 코드 · 소셜 코드): 15분짜리 액세스 토큰이 영구 자격으로 바뀌지 못하게. 선언된 범위만(400 `API_KEY.SCOPE_UNKNOWN`) · 활성 키 상한(409 `API_KEY.LIMIT_REACHED`) · 만료 상한(400 `API_KEY.EXPIRES_INVALID`) |
| `DELETE /api/v1/api-keys/{id}` | 회수 → 204(이미 회수된 키도 204). 남의 키 · 없는 키는 404 `API_KEY.NOT_FOUND` |
| `GET /api/v1/api-keys/scopes` | 선언된 범위 `[{key, module}]` |

**운영자 경로**(`skeleton.api-key.admin.enabled=true` 일 때만 - 모듈 기본 꺼짐, 꺼져 있으면 경로가 없어 404; 익명 401 · 권한 없음 403; 키로는 닿지 않는다):

| 메서드 · 경로 | 권한 | 하는 일 |
|---|---|---|
| `GET /api/v1/admin/api-keys?ownerId&status&cursor&size` | `api-key.read` | 모든 주인의 키, 새것부터 커서(`size` 기본 50 · 최대 200). 줄 `{id, ownerId, ownerName, name, hint, scopes, status, revokedBy, revokedReason, createdAt, expiresAt, lastUsedAt}` - 토큰 · 해시는 없다. `ownerName`(이메일 또는 아이디)은 `account.read` 도 가진 운영자에게만 오고 아니면 `null`. 알 수 없는 `status` 는 400 |
| `POST /api/v1/admin/api-keys/{id}/revoke {reason}` | `api-key.revoke` | 회수 → 204. 사유 필수(비면 400, 200자 이하). 없는 키 404 `API_KEY.NOT_FOUND`, 이미 회수된 키는 204 그대로(바뀐 것이 없어 기록도 없다). `revoked_by` = **운영자 계정 id** 라 주인의 목록에는 `revokedBy: OPERATOR` 로 보인다. 키는 다음 요청부터 401 `API_KEY.REVOKED` |

권한 `api-key.read` · `api-key.revoke` 는 기본 역할이 없고 전권(`ADMIN`)만 가진다 - 역할 관리에서 나눠 준다.
**기록**: 운영 회수는 `OperatorAuditLog` 에 `api-key.key.revoked`(대상 = 키 id, 사유 원문 없음)를 남긴다. 본인 발급 · 회수와 운영 회수는 모두 `account` 이벤트 `API_KEY_CREATED` · `API_KEY_REVOKED` 를 낸다 - 계정 = 키의 주인, detail `keyId` · `by`(만든 · 회수한 계정). 토큰 · 해시 · 사유는 싣지 않는다. 회수 사유는 `revoked_reason` 칸에만 있고 운영 목록에서만 보인다.

**소유자**: 1차는 계정(`ACCOUNT`, 본인만 — `AccountApiKeyOwnerPolicy`). `ApiKeyOwnerPolicy(ownerType, canManage(caller, owner))` 가 소유자 종류의 확장점이다 — 앱이 같은 종류의 정책 빈을 두면 대신한다(「유료 요금제만 발급」). 회전은 「새로 만들고 옛것 회수」.
**표 한 줄의 회수 칸**: `revoked_by`(회수한 계정) · `revoked_reason`(운영 회수의 사유)은 운영 회수가 들어올 자리라 이미 있다. 응답의 `revokedBy` 는 이 값과 주인을 비교해 `USER`/`OPERATOR` 로 낸다.
**탈퇴**: `AccountErasureListener`(`api-key`)가 그 계정이 소유한 키를 모두 지운다.

**동작 메모**: ① 활성 키 상한은 세고 넣는 두 문장이라 동시 발급이 겹치면 상한을 한두 개 넘을 수 있다(자원 보호용이라 허용). ② 키 요청마다 주인 계정 한 줄을 읽는다(정지 · 탈퇴 대기 판정). ③ 토큰 원문은 로그 · 이벤트 · 저장 어디에도 없다(시험으로 훑는다) — 모듈은 토큰을 로그에 쓰지 않으며 키 id 만 쓴다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:api-key"))` |
| 함께 오는 모듈 | `account`, `auth`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.api-key` — [docs/config/modules/api-key.yml](../config/modules/api-key.yml) |
| 기본 동작 | 켜짐. 사용자 경로 켜짐, 운영자 경로 꺼짐(404), 접두사 `apk`, 활성 키 10개 상한, 키별 한도 꺼짐, 만료 상한 없음. |
| 부팅에 필요한 것 | 저장소 포트 `ApiKeyStore` — `api-key-jdbc` 가 낸다. 없으면 기동에 실패한다. 사용자 경로는 `account` 의 `Reauth` 가 필요하다. 운영자 경로는 `OperatorAccess`(`platform`)가 판정한다. |
| 교체 지점 | `ApiKeyStore`, `ApiKeyOwnerPolicy`, `ApiKeys`, `ApiKeyTokens`, `ApiKeyScopes`, `ApiKeyController`, `ApiKeyAdminController`, `apiKeyPermissionDeclaration`, `apiKeyAuthenticator`, `apiKeyAccessConfigurer` |
| 마이그레이션 | 없음 (스키마는 `api-key-jdbc`) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/api-key/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
