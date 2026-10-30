# 2단계 인증(TOTP) · API 키 — 설계 초안

> 2026-10-30. 요청: 보안 묶음 두 갈래. ① TOTP 2단계 인증(등록 · 로그인 두 번째 단계 · 복구 코드 · 끄기 · 운영자 해제 · 앱이 필수로 정하기) ② B2B 용 API 키(발급 · 범위 · 만료 · 회수 · 요청 인증 · 키별 한도 · 운영 기록). 두 갈래 모두 사용자 화면 · 운영 화면 · 견본 앱 · e2e 한 여정까지 간다.
> 형 원칙: 조립식 · 캡슐화 / 모듈 기본은 중립, 앱 yml 에서 덮어쓴다 / 업계 관행(보안은 RFC · OWASP) / 공개 레포라 개인 흔적 없음(`example.com`).
> 대상: kotlin-skeleton(새 `auth-mfa` · `auth-mfa-jdbc` · `api-key` · `api-key-jdbc`, 고리만 더할 곳 `platform` · `auth` · `account` · `apps/sample`) + react-skeleton(`@skeleton/auth` 본체 · 새 하위 경로 `/api-keys` · `/admin`, `apps/sample`). 기준: kotlin main `2e6aaae4`, react main `cebcc0f`.
> 읽은 코드: `auth` 의 `AuthTokenResponseFactory` · `PasswordLoginService` · `LoginHooks` · `SessionContracts`(`SessionInspector`) · `JwtAuthenticationFilter` · `AuthAutoConfiguration.securityFilterChain` · `account` 의 `Reauth` · `challenge/Challenges`(`ChallengeStore`, `CodeHasher` 키 유도) · `signin/AccountSignInService` · `abuse/LoginControls` · `events/AccountEvents` · `mail/AccountMail` · `platform` 의 `RateLimitStore` · `OperatorAuditLog` · `PermissionDeclaration` · `crypto` 문서 · `board` 의 `StoreBoardRateLimiter` · react `ReauthProof` · `pendingDeletion` · `AccountSettings` · 같은 날의 워크스페이스 설계 초안(`2026-10-30-org-design.md` — API 키 소유자 `WORKSPACE` 자리를 이 문서와 맞췄다).

## 0. 국룰 조사 → 권장안

| 물음 | 업계 · 표준 | 권장 |
|---|---|---|
| TOTP 매개변수 | RFC 6238: 30초 · T0=0, 지연용으로 **한 걸음 뒤까지만** 권함. RFC 4226: 비밀 128비트 이상, **160비트 권장**. Google Authenticator 는 SHA1 · 6자리 · 30초 외에는 무시한다 | SHA1 · 6자리 · 30초 · 비밀 160비트 · 앞뒤 한 걸음(±1) 허용. 매개변수는 고정이다(설정으로 열지 않는다 — 인증 앱 호환이 깨진다) |
| 같은 코드 두 번 | RFC 6238 §5.2: 검증에 한 번 성공한 OTP 의 두 번째 시도는 **받으면 안 된다(MUST NOT)** | 계정에 `last_used_step` 을 두고 조건부 UPDATE(`< :step` 일 때만)로 원자적으로 막는다 |
| 어느 로그인에 거나 | OWASP MFA: 로그인 방법이 여럿이면 **모두에** MFA 를 건다 | 비밀번호 · 소셜 · 매직 링크 · 탈퇴 취소 모두 — `AuthTokenResponseFactory.issue` 한 곳에서 건다(새로고침은 제외 — 이미 통과한 세션이다) |
| 두 번째 단계 응답 | Auth0: `403 mfa_required` + `mfa_token`. 이 레포: 탈퇴 대기가 `403 AUTH.ACCOUNT_DELETION_PENDING` + `data.restoreToken` | `403 AUTH.MFA_REQUIRED` + `data{mfaToken, methods, expiresAt}` — 프런트의 기존 「로그인 응답이 403 + data」 처리와 같은 꼴 |
| 복구 코드 | OWASP: 등록 때 1회용 코드 여러 개. GitHub · GitLab: 10개 | 10개, 각 16자 base32(80비트, `xxxx-xxxx-xxxx-xxxx`). 엔트로피가 높아 SHA-256 해시로 충분(OWASP — 해시는 실수 노출을 줄이려는 것) |
| 비밀 저장 | OWASP: 시드를 평문으로 오래 두지 않는다. TOTP 비밀은 서버가 다시 읽어야 하므로 해시는 못 한다 | `crypto` 의 `TextEncryptor`(AES-GCM, 키 id 봉투 → 키 회전) — 결제 빌링키와 같은 선례 |
| 설정 변경 | OWASP: MFA 끄기 · 관리자 승격 · 이메일 변경은 민감한 일. 지원 담당의 해제는 엄격한 본인 확인 뒤에만 | 등록 · 재등록 · 복구 코드 재발급 = 다시 인증(`account` 의 `Reauth`). **끄기 = 다시 인증 + 지금 코드(TOTP 또는 복구 코드)**. 운영자 해제는 권한 `mfa.reset` + 사유 + 대상에게 메일 + 모든 세션 종료 |
| 실패 알림 | OWASP: 비밀번호는 맞고 두 번째에서 실패하면 사용자에게 알린다(시각 · 브라우저 · 위치) | 계정 한도에 걸리면 메일 한 통 「비밀번호는 맞았지만 2단계에서 막혔어요」 + 계정 이벤트 |
| 필수로 정하기 | OWASP: **관리자 · 고권한 사용자는 MFA 필수**. GitHub: 기한 안에 등록하게 하고 그 뒤 막음 | `required-for: NONE`(모듈 기본) `| OPERATORS | ALL`. 안 한 사람은 로그인은 되지만 등록 말고 모든 API 가 `403 MFA.ENROLLMENT_REQUIRED` — 이 레포의 이메일 필수 · 로그인 아이디 문과 같은 꼴 |
| API 키 모양 | GitHub `ghp_` · Stripe `sk_live_`: **알아볼 수 있는 접두사**(비밀 검출용) + 무작위. 저장은 해시만, 화면엔 한 번만 | `<접두사>_<base62 32자>`(≈190비트), 저장은 SHA-256 해시(높은 엔트로피라 느린 해시 불필요 — OWASP), 화면 힌트는 `접두사…끝 4자` |
| API 키 권한 | GitHub fine-grained PAT · Stripe restricted key: **필요한 범위만** 고른다. OWASP API Top 10: BFLA(기능 수준 권한) | 범위는 앱 · 모듈이 선언하고, 키로는 **표시한 경로만** 닿는다(기본 거부) — 결정 1 |
| 만료 · 회수 · 마지막 사용 | GitHub: 만료일 고르기(최대값은 조직이 정함) · 마지막 사용 표시 · 회수. Stripe: 회수 · 마지막 사용 | 만료는 만들 때 고른다(최대는 앱 설정, 모듈 기본 상한 없음) · 본인 · 운영자 회수 · `last_used_at` 은 1분 해상도로만 쓴다(쓰기 증폭 방지) |
| 헤더 | Stripe · OpenAI · GitHub: `Authorization: Bearer <키>` | 같다. JWT 와는 접두사로 가른다 — 헤더는 한 가지 |
| 만들 때 다시 인증 | GitHub: PAT 만들기는 sudo 모드(다시 인증) | `Reauth` — 15분짜리 액세스 토큰이 영구 자격으로 바뀌지 못하게(이 레포 `Reauth` 의 목적 그대로) |

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 2단계 등록 | TOTP 하나(계정당 하나) · QR + 비밀 문자열 · 첫 코드로 확인 · 확인 때 복구 코드 10개 한 번 보여 줌 · 재등록(새 폰) | 패스키/WebAuthn · 여러 인증 앱 · SMS · 이메일 코드 2단계 |
| 로그인 | 모든 로그인 방법 뒤 두 번째 단계(TOTP 또는 복구 코드) · 시도 한도 · 재사용 방지 · 실패 알림 | **신뢰 기기 기억**(결정 3) · 민감한 일마다 다시 2단계(step-up, JWT `amr`) · OIDC 제공자의 `amr` 믿기 |
| 관리 | 상태 보기 · 복구 코드 재발급 · 끄기 · 운영자 강제 해제 · 앱 필수 설정(`NONE`/`OPERATORS`/`ALL`) | 필수의 유예 기간 · 운영 계정 목록의 2단계 열 · 셀프 복구(결정 4 가 B 면 1차) |
| API 키 | 계정 소유 키 · 이름 · 범위 · 만료 · 본인/운영자 회수 · 마지막 사용(시각 · IP) · 키별 한도(앱이 켬) · 운영 목록 | 소유자 `WORKSPACE`(워크스페이스 모듈이 `ApiKeyOwnerPolicy` 구현) · 키 회전(겹치는 기간) · 체크섬 꼬리(오프라인 형식 검사) · 키별 한도 덮어쓰기 · 요청 기록 · 허용 IP · 운영 권한 범위 · 키 자기 조회 |
| 화면 | 로그인 2단계 화면 · 보안 설정(2단계 · API 키) · 등록 필수 문 · 운영: 계정 2단계 해제 · API 키 목록/회수 | 보안 활동 기록 화면 |

**비목표**: 자체 OAuth 서버(클라이언트 자격 증명 흐름) · 서명 요청(HMAC) · SMS/음성 · 하드웨어 키 · 위험 기반 인증 · `break-glass` 경로에 2단계 걸기(비상문이 목적이다).
**반목표**: 두 번째 단계를 건너뛰는 로그인 길이 하나라도 남는다 / 같은 TOTP 코드가 두 번 통과한다 / 복구 코드 · 키 · TOTP 비밀이 평문으로 저장 · 로그 · 이벤트에 남는다 / 키로 운영 경로나 표시하지 않은 경로에 닿는다 / 15분짜리 액세스 토큰만으로 2단계를 끄거나 키를 만든다 / 이 모듈들을 안 붙인 앱의 동작이 바뀐다.

## 2. 모듈 경계 · 확장 지점

```
auth-mfa(-jdbc) ──▶ account{Reauth, ChallengeStore, AccountEventPublisher, AccountMailer}, auth, crypto{TextEncryptor}, platform
api-key(-jdbc)  ──▶ account{Reauth, AccountEventPublisher}, auth, platform{RateLimitStore, OperatorAuditLog, ApiKeyScope}
auth   (고리만)  : SecondFactorGate · BearerTokenAuthenticator — 비어 있으면 지금과 같다
platform(선언만) : @ApiKeyScope · ApiKeyScopeDeclaration — authz 의 PermissionDeclaration 처럼 판정 모듈 없이 선언할 수 있게
```

- 두 갈래는 서로를 모른다. 둘 다 `account` 에 기대는 까닭은 하나 — 민감한 일 앞의 **다시 인증**(`Reauth`)이 거기 있다. `auth-magic-link` 와 같은 방향이다.
- `auth-mfa` 의 로그인 대기는 새 표 없이 `account` 의 `ChallengeStore`(용도 `mfa_login`)를 쓴다 — 시도 차감 · 한 번만 지우기가 이미 원자적이고 청소도 있다(매직 링크가 `OneTimeTokens` 를 다시 쓴 것과 같은 판단).

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `SecondFactorGate`(`challenge(account): SecondFactorChallenge?`) | `auth` | 모든 로그인이 지나는 `AuthTokenResponseFactory.issue` 한 곳에 건다 — 「모든 로그인 길」(OWASP)을 구조로 지킨다. 두 번째 구현(패스키)이 다음 목록에 있다 |
| `BearerTokenAuthenticator`(`supports(token)` · `authenticate(token, request)`) | `auth` (`JwtAuthenticationFilter` 가 먼저 묻는다) | `Authorization: Bearer` 해석을 한 필터에 둔다. 체인을 앱이 바꿔도 키가 따라간다. 두 번째(워크스페이스 · 서비스 토큰)가 보인다 |
| `@ApiKeyScope("notes.read")` · `ApiKeyScopeDeclaration(module, scopes)` | `platform` | 앱 · 다른 모듈이 `api-key` 에 의존하지 않고 「키로 열 경로와 범위」를 선언한다. `api-key` 가 없으면 아무 일도 안 한다 |
| `ApiKeyOwnerPolicy`(`canManage(caller, owner)`) | `api-key` | 소유자 종류 확장점. 1차 구현 `ACCOUNT`(본인만). 워크스페이스 설계가 `WORKSPACE`(ADMIN 이상) 구현을 다음 판으로 잡았다. 앱이 「유료 요금제만 발급」 같은 제한도 여기서 건다 |
| (다음 판 맞춤 — 워크스페이스 설계와 합의) `WORKSPACE` 소유 키 | `workspace` 가 구현 | owner type `WORKSPACE` · id `ws_…`. `canManage` 는 전역 권한이 아니라 **멤버십 역할**(ADMIN 이상)로 판정 — 정책은 `OperatorRef.accountId` 만 꺼내 쓴다(서비스 관리자라도 멤버가 아니면 거절). 키 요청의 주체 `accountId` = `created_by`, 요청에 실린 `ApiKeyOwner` 를 워크스페이스 필터가 「지금 워크스페이스」로 먼저 읽는다. **만든 사람이 추방 · 탈퇴 · 계정 삭제되면 그 사람이 만든 워크스페이스 키는 회수**(GitHub 조직 소유 토큰과 같다). 워크스페이스 1차에는 멤버가 빠졌다는 고리가 없으므로 **키 요청마다 `created_by` 의 멤버십을 다시 읽어** ADMIN 미만이면 그 자리에서 거절 + 회수 표시(워크스페이스 필터도 요청마다 멤버십을 읽어 추가 비용은 거의 없다. `WorkspaceMemberListener` 는 두 번째 사용자가 생길 때). 1차 표 · SPI 는 이 셋을 위해 바꿀 것이 없다 |
| `MfaStore` · `ApiKeyStore` | 각 모듈, 구현 `*-jdbc` | 다른 모듈과 같은 포트/어댑터 짝. 메모리 구현은 두지 않는다(`authz` 선례 — 없으면 기동 실패) |
| `MfaPolicy`(`required(account): Boolean`, 기본은 `required-for` 설정) | `auth-mfa` | 「요금제 · 역할별 필수」 같은 앱 고유 규칙을 빈 하나로 |

## 3. 2단계 인증 (`auth-mfa`)

**데이터** (`auth-mfa-jdbc`, 두 방언, 시각 PG `timestamptz` · MySQL `datetime(6)` UTC)
- `mfa_totp(account_id pk, secret_enc varchar(512) null, enabled_at null, last_used_step bigint null, pending_secret_enc varchar(512) null, pending_expires_at null, updated_at)` — 계정당 한 줄. 켜짐 = `secret_enc` 있음. 재등록은 `pending_*` 에 두었다가 확인 때 한 문장으로 옮긴다(옛 비밀은 새 것이 확인될 때까지 산다).
- `mfa_recovery_codes(id pk 'mfr_', account_id, code_hash char(64), used_at null, created_at, unique(account_id, code_hash))` — 재발급 = 지우고 10줄. 쓰기 = `update … set used_at = :now where account_id = ? and code_hash = ? and used_at is null`(한 요청만 이긴다).
- 로그인 대기: `account_challenges`(용도 `mfa_login`, id = `mfaToken` 의 SHA-256, 시도 5, 5분, IP).

**등록** — `POST /auth/mfa/totp/enrollment {다시 인증}` → `{secret, otpauthUri, qr{size, path}, expiresAt}`(10분, `no-store`). `issuer` = `skeleton.auth-mfa.totp.issuer`(비면 `spring.application.name`), 이름 = 이메일 → 닉네임 → 계정 id 앞자리. → `POST …/enrollment/confirm {code}` → 첫 등록이면 `{recoveryCodes[10]}`(한 번뿐), 재등록이면 `{recoveryCodes: null}`(기존 코드 유지). 켤 때 **이 세션 말고 모든 세션을 닫는다**(비밀번호 변경과 같다 — 그래서 살아 있는 세션은 모두 2단계를 지난 세션이 된다). 등록 필수 문에 걸린 계정은 **방금 로그인한 세션(15분 안, `SessionInspector`)이면 다시 인증 없이** 등록한다 — 첫 이메일 등록의 면제와 같은 이유.

**로그인** — `issue(account)` → 막힌 계정 거르기(지금 그대로) → `SecondFactorGate` 가 켜진 계정이면 대기 한 줄 + `403 AUTH.MFA_REQUIRED {mfaToken, methods: ["totp", "recovery_code"], expiresAt}`. → `POST /auth/mfa/verify {mfaToken, code | recoveryCode}`(공개) → 맞으면 대기를 지우고 `issueAfterSecondFactor` 로 세션 · 토큰(로그인과 같은 응답 · 같은 쿠키 전달).
- 비밀번호 로그인의 고리: 1단계 통과 = `LoginHooks.onSuccess`(소셜 · 매직 링크가 1단계에서 로그인을 기록하는 것과 맞춘다). 2단계 결과는 `MFA_VERIFIED` · `MFA_FAILED` 이벤트로 따로 남는다.
- 비밀번호 재설정은 2단계를 끄지 않는다(복구 길이 MFA 를 우회하면 안 된다 — OWASP).

**한도 · 재사용** — 대기 하나에 5번(다 쓰면 `410 MFA.CHALLENGE_EXPIRED`, 1단계부터 다시) · 계정마다 틀린 시도 15분에 10번 · 하루 30번(`RateLimitStore`, 맞으면 `release` — 틀린 것만 센다) → `429 MFA.TOO_MANY_ATTEMPTS` + `Retry-After` + 창마다 한 번 메일 · `MFA_THROTTLED`. 계산: 한 번 맞힐 확률 3/10⁶ × 하루 30번 × 365일 ≈ 3%/년 — 비밀번호가 이미 샌 경우의 값이고, 첫 막힘에서 주인이 메일을 받는다. 같은 걸음의 코드는 `last_used_step` 조건부 UPDATE 로 거른다(틀린 것으로 센다). 등록 확인도 계정 한도를 같이 쓴다.

**끄기 · 재발급 · 상태** — `GET /auth/mfa` → `{enabled, enabledAt, recoveryCodesRemaining, required}`. `POST /auth/mfa/recovery-codes {다시 인증}` → 새 10개. `POST /auth/mfa/disable {다시 인증, code | recoveryCode}` → 204 · 두 표 지움 · 메일. 필수 대상이면 `409 MFA.REQUIRED_BY_POLICY`(재등록은 된다).

**운영자 해제** (`skeleton.auth-mfa.admin.enabled`, 기본 꺼짐 → 404) — `GET /admin/mfa/accounts/{id}`(권한 `account.read`) · `POST …/{id}/reset {reason}`(권한 `mfa.reset`, 기본 역할 없음): 자기 자신 `409 MFA.SELF_RESET` · 자기에게 없는 권한을 가진 계정 `403 MFA.ESCALATION`(authz 의 상승 규칙) → 두 표 지움 · 대상의 **모든 세션 종료** · 대상에게 메일 · `MFA_RESET_BY_ADMIN` + `OperatorAuditLog`(`mfa.credential.reset`, 대상 id 만). 모듈 문서에 「본인 확인 절차 없이 해제하지 말 것」(OWASP 지원 담당 경고).

**필수** — `skeleton.auth-mfa.required-for: NONE | OPERATORS | ALL`(`OPERATORS` = `OperatorAccess` 권한이 하나라도 있는 계정). 대상인데 등록 전이면 로그인은 되고, MVC 인터셉터가 허용 목록(`/auth/**` · `GET /account/me` · `/account/reauth/confirmation` · `required.extra-exclude-paths`) 밖을 `403 MFA.ENROLLMENT_REQUIRED` 로 막는다. API 키 요청에는 걸지 않는다(키는 사람이 아니다). 계정 상태는 요청마다 기본키 한 번 읽기 + 30초 캐시.

**탈퇴 · 이벤트 · 메일** — `AccountErasureListener` 가 두 표를 지운다. `account` 에 이벤트 `MFA_ENABLED` · `MFA_DISABLED` · `MFA_RESET_BY_ADMIN` · `MFA_RECOVERY_CODES_REGENERATED` · `MFA_RECOVERY_CODE_USED` · `MFA_VERIFIED` · `MFA_FAILED` · `MFA_THROTTLED`(detail 에 코드 · 비밀 없음), 메일 `MailKind.MFA_NOTICE` 하나(`vars.change` = enabled · disabled · reset · recovery_code_used · throttled, ko/en, 골든). 기동: `TextEncryptor` · `MfaStore` 가 없으면 이름을 적고 실패.

## 4. API 키 (`api-key`)

**데이터** (`api-key-jdbc`) — `api_keys(id pk 'key_', owner_type varchar(32) 'ACCOUNT', owner_id varchar(64), created_by, name varchar(100), token_hash char(64) unique, token_hint varchar(24), scopes varchar(1000) 공백 구분, expires_at null, revoked_at null, revoked_by null, revoked_reason varchar(200) null, last_used_at null, last_used_ip varchar(45) null, created_at, index(owner_type, owner_id, created_at))`.

**발급** — `POST /api-keys {name, scopes[], expiresAt?, 다시 인증}` → `201 {key, token}`(토큰은 이 응답에만, `no-store`). 검사: `ApiKeyOwnerPolicy.canManage` · 선언된 범위만(`400 API_KEY.SCOPE_UNKNOWN`) · 활성 키 상한 `max-active-per-owner`(기본 10, `409 API_KEY.LIMIT_REACHED`) · `max-lifetime`(기본 없음). 회전은 「새로 만들고 옛것 회수」.

**요청 인증** — `JwtAuthenticationFilter` 가 Bearer 토큰을 `BearerTokenAuthenticator` 들에게 먼저 묻는다. `<token-prefix>_` 로 시작하면 키 해석기가 해시로 한 줄 찾기 → 없음 `401 API_KEY.INVALID` · 만료 `401 API_KEY.EXPIRED` · 회수 `401 API_KEY.REVOKED`(토큰을 가진 사람에게만 보이므로 이유를 알려 준다) · 주인이 막힘(정지 · 탈퇴 대기, `AuthAccount.loginBlock`) `403 API_KEY.OWNER_BLOCKED`. 통과하면 주체 = `CurrentPrincipal(accountId = 주인, roles = ∅)` + `keyId` · `scopes` · `owner` — `Authentication.name` 을 쓰는 기존 컨트롤러가 그대로 돈다. 역할이 비어 운영 경로(`OperatorAccess`)는 늘 403. `last_used_at` · IP 는 1분보다 오래됐을 때만 조건부 UPDATE.

**범위 · 기본 거부** — MVC 인터셉터(`ApiKeyAccessInterceptor`, 다른 문보다 먼저): 키로 온 요청은 처리기에 `@ApiKeyScope` 가 있어야 하고(없으면 `403 API_KEY.NOT_ALLOWED`), 키가 그 범위를 가져야 한다(`403 API_KEY.SCOPE_MISSING`, `data.scope`). 범위 키 규칙은 권한 키(`PermissionKeys`)와 같은 모양(`notes.read`), 운영 권한과는 다른 이름 공간이다(결정 1). 이메일 필수 · 재동의 같은 사람용 문은 키에도 그대로 걸린다(주인이 풀어야 한다).

**키별 한도** — `skeleton.api-key.rate-limit.capacity`(모듈 기본 0 = 끔) · `window`(1분). 켜면 `RateLimitStore` 키 `api-key:<id>`(`redis-rate-limit` 이 있으면 Redis) → `429 API_KEY.RATE_LIMITED` + `Retry-After` + platform 한도 필터와 같은 머리글.

**경로** (사용자 `skeleton.api-key.http.enabled` 붙이면 켜짐 · 운영 `admin.enabled` 기본 꺼짐 → 404)

| 경로 | 하는 일 | 권한 |
|---|---|---|
| `GET /api/v1/api-keys` | 내 키 `[{id, name, hint, scopes, status ACTIVE·EXPIRED·REVOKED, revokedBy USER·OPERATOR, createdAt, expiresAt, lastUsedAt}]` — 토큰 · 해시 · 운영자 사유 없음 | 로그인(키 요청은 거부) |
| `POST /api/v1/api-keys` · `DELETE /api/v1/api-keys/{id}` | 발급(다시 인증) · 회수(204, 남의 키 404) | 로그인 · 본인 |
| `GET /api/v1/api-keys/scopes` | 선언된 범위 `[{key, module}]` | 로그인 |
| `GET /api/v1/admin/api-keys?ownerId&status&cursor` | 전체 목록(최신순 커서, 계정 이름은 `account.read` 도 있을 때만 — 쿠폰 · 배지 선례) | `api-key.read` |
| `POST /api/v1/admin/api-keys/{id}/revoke {reason}` | 회수(사유 필수) · `OperatorAuditLog`(`api-key.key.revoked`) | `api-key.revoke` |

**기록** — 본인 발급 · 회수는 `account` 이벤트 `API_KEY_CREATED` · `API_KEY_REVOKED`(detail `keyId` · `by`), 운영자 회수는 거기에 `OperatorAuditLog` 를 더한다. 토큰은 어떤 로그 · 이벤트에도 없다(`logging.md` 의 가림 규칙 + 시험). 탈퇴: `AccountErasureListener` 가 그 계정 소유 키를 지운다.

## 5. 화면 (react)

- `@skeleton/auth` 본체: `mfaRequiredOf(error)`(탈퇴 대기의 `deletionPendingOf` 와 같은 꼴) · `TwoFactorChallengeScreen`(6자리 `CodeEntry` + 「복구 코드로 들어가기」 전환, 남은 시도 · 만료 안내) — 비밀번호 로그인 · 소셜 콜백 · 매직 링크 착지 · 탈퇴 취소가 모두 이 화면으로 넘긴다(`mfaToken` 은 라우터 state 에만, 주소 · 저장소에 두지 않는다). `createAuthRoutes` 에 `/login/two-factor`.
- 계정 설정 새 칸 `twoFactor`: 상태 · 「켜기」 마법사(`ReauthProof` → QR(`qr.path` 를 `<svg><path>` 로) + 비밀 문자열 + 「앱에서 바로 열기」(`otpauth://`) → 코드 확인 → 복구 코드 보기 · 복사 · 내려받기 · 「저장했어요」 확인) · 복구 코드 재발급 · 끄기(다시 인증 + 코드). `MfaEnrollmentRequiredScreen`(이메일 필수 문과 같은 자리).
- 새 하위 경로 `@skeleton/auth/api-keys`: `ApiKeysSection`(목록 · 만들기 대화상자: 이름 · 범위 체크 · 만료 고르기 30/90/365일 · 없음(앱이 상한을 두면 숨김) · `ReauthProof` → 토큰 한 번 보기 + `CopyButton` + 「다시 볼 수 없어요」 · 회수 `ConfirmDialog`), 범위 이름은 앱의 `scopeLabels` prop.
- `@skeleton/auth/admin`: `AdminAccounts` 줄 메뉴에 「2단계 인증 해제」(`can('mfa.reset')`, 사유 필수) · `AdminApiKeys`(표 · 필터 · 회수 대화상자). 문구 ko/en, 부품마다 스토리 + `play`, 화면은 Patterns(설정 · 목록 · 폼)에서 복사, 눌리는 것은 눌리게.

## 6. 견본 앱

- kotlin `apps/sample`: `auth-mfa` · `auth-mfa-jdbc` · `api-key` · `api-key-jdbc`. yml: `auth-mfa.totp.issuer: Notes` · `admin.enabled: true` · `required-for` 는 결정 2 · `api-key.token-prefix: notes` · `rate-limit.capacity: 60` · `max-lifetime: 365d` · `admin.enabled: true`. 노트 범위 선언 `notes.read` · `notes.write`, `NoteController` 읽기에 `@ApiKeyScope("notes.read")`, 만들기 · 고치기 · 지우기에 `notes.write`.
- react `apps/sample`: 설정에 두 칸, 운영 메뉴에 「API 키」, 계정 표 줄 메뉴.
- 눌러 볼 흐름: ① user 가 2단계를 켠다(QR · 코드 · 복구 코드) → 로그아웃 → 로그인 → 코드 → 들어감 ② 복구 코드로 로그인 → 남은 수 9 ③ 같은 코드 다시 → 거절 ④ admin 이 user 의 2단계를 해제(사유) → user 는 코드 없이 로그인 ⑤ user 가 키를 만든다(`notes.read`) → 키로 `GET /api/v1/notes` 200 · `POST` 403 · `/account/me` 403 → 회수 → 401 ⑥ admin 이 다른 키를 회수 → 사용자 목록에 「운영자가 회수」.

## 7. 검증 (깨지면 사고 나는 것만)

**kotlin**
- TOTP: RFC 6238 부록 B 시험 벡터(SHA1) · base32 왕복 · ±1 걸음 통과 · ±2 거절 · 같은 걸음 두 번 → 두 번째 거절(동시 16스레드도 하나만, 두 방언 `dbTest`).
- 모든 로그인 길: 2단계가 켜진 계정으로 비밀번호 · 소셜(가짜 제공자) · 매직 링크 · 탈퇴 취소가 모두 `403 AUTH.MFA_REQUIRED` 이고 토큰 · 세션이 생기지 않는다. 새로고침은 그대로. 2단계 모듈이 없는 조립(`apps/api`)의 로그인 응답이 바이트까지 같다.
- 한도: 대기당 5번 뒤 410, 계정 10번/15분 뒤 429 + 메일 한 통, 맞은 시도는 세지 않는다. 복구 코드 한 개를 동시에 두 번 → 하나만.
- 다시 인증: 다시 인증 없이 등록 · 재발급 · 끄기 · 키 발급 → 거절, 등록 필수 문 + 15분 안 세션은 등록 면제, 끄기는 코드도 필요, 필수 대상의 끄기 409.
- 저장 · 로그: DB 의 `secret_enc` 가 `aes-gcm:v1:` 봉투, 복구 코드 · 키 · 비밀 · `mfaToken` 원문이 DB · 로그 · 이벤트 어디에도 없다(값 훑기).
- 운영 해제: 꺼짐 404 · 권한 없음 403 · 자기 409 · 상승 403 · 대상 세션 전부 닫힘 · 메일 · 운영 기록.
- 키: 표시 없는 경로 403 · 범위 없음 403 · 운영 경로 403 · 만료 · 회수 · 모르는 키 401 · 주인 정지 403 · 남의 키 회수 404 · 한도 429(켰을 때) · `last_used_at` 1분에 한 번 쓰기 · 탈퇴 때 지움 · 기존 JWT 요청은 그대로.
- 조립: 각 모듈 혼자(`crypto` 키 없음 → `auth-mfa` 기동 실패 메시지), `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest` · 두 방언.

**react**: 2단계 화면 스토리(코드 · 복구 코드 전환 · 틀림 · 만료 · 한도), 설정 마법사 단계 `play`(복구 코드 「저장했어요」 전에는 닫히지 않는다), 키 만들기 → 토큰 한 번 보기 → 닫으면 다시 못 봄, 관리 회수 사유 필수, api 경로 기록 시험, `capabilities` 검사.
**e2e**(진짜 백엔드): §6 ① ~ ⑥ — TOTP 는 시험 도우미가 비밀로 계산(Node `crypto`, 20줄), ⑤ 는 브라우저 밖 `fetch` 로 키 호출.

## 내가 정한 것 (관행대로 — 형이 뒤집을 수 있다)

1. **모든 로그인 길에 2단계** — 소셜 · 매직 링크 포함(OWASP 「모든 길」). 제공자의 MFA 를 믿는 것(`amr`)은 다음 판.
2. **두 번째 단계 응답은 403 + data** — 탈퇴 대기와 같은 꼴이라 프런트 처리가 하나로 묶이고, 로그인 응답 타입이 둘로 갈라지지 않는다.
3. **TOTP 고정값** SHA1 · 6자리 · 30초 · ±1 걸음 · 160비트. 설정으로 열지 않는다(인증 앱 호환).
4. **비밀은 `crypto` 로 암호화, 복구 코드 · 키는 SHA-256** — 복구 코드를 JWT 비밀에서 유도한 HMAC 으로 하지 않은 까닭: JWT 비밀을 돌리면 오래 사는 복구 코드가 모두 죽는다. 대신 80비트로 길게 만든다.
5. **켤 때 다른 세션 모두 종료 · 비밀번호 재설정은 2단계를 끄지 않음 · 운영자 해제는 모든 세션 종료.**
6. **끄기만 「다시 인증 + 코드」, 재등록 · 재발급은 「다시 인증」만** — 켤 때 다른 세션을 닫으므로 살아 있는 세션은 모두 2단계를 지난 세션이다.
7. **로그인 대기는 `account` 의 `ChallengeStore` 재사용**(새 표 없음). 맞지 않으면 개발자가 `auth-mfa-jdbc` 에 표를 하나 더한다 — 그때는 보고에 적는다.
8. **QR 은 서버가 만든다** — `io.nayuki:qrcodegen`(MIT, 의존 없음)으로 모듈 행렬을 SVG 경로 문자열 하나(`qr.path`)로 내려 준다. 프런트에 QR 라이브러리를 들이지 않고, `innerHTML` · data URI(CSP) 없이 `<path d>` 로 그린다. `otpauth://` 를 바깥 QR 서비스로 보내는 일은 없다.
9. **API 키 헤더는 `Authorization: Bearer` 하나, 접두사로 JWT 와 가른다** · 모듈 기본 접두사 `apk`(중립), 앱이 바꾼다(견본 `notes`) — 비밀 검출 규칙을 앱마다 걸 수 있게.
10. **키는 주인 계정으로 움직이되 역할은 비운다** — 운영 경로는 구조적으로 닫힌다. 사람용 문(이메일 필수 · 재동의)은 키에도 걸린다.
11. **키 한도 · 만료 상한은 모듈 기본 없음(끔), 견본이 켠다** — 스켈레톤은 사용 방식을 정하지 않는다. 활성 키 10개 상한만 자원 보호로 기본에 둔다.
12. **프런트 API 키 화면은 새 패키지가 아니라 `@skeleton/auth/api-keys`** — 다시 인증 부품(`ReauthProof`)을 그대로 쓰고, 배지가 `@skeleton/rewards/badge` 로 간 선례와 같다(패키지 하나 = 카탈로그 · 찍기 비용).
13. **2단계 성공 기록**: 비밀번호 1단계 통과도 `LOGIN_SUCCESS`(다른 수단과 같은 뜻 — 「1단계 통과」), 2단계는 `MFA_*` 이벤트로 따로.

## 작업 계획

두 줄기는 서로 독립이다. 같은 파일을 만지는 곳(아래 「겹침」)만 합칠 때 순서를 지킨다. 크기 S < 1.5시간 · M 2~3시간 · L 4~6시간(에이전트 작업 시간, 리뷰 · 반영 제외).

**줄기 A — 2단계 인증**

| 조각 | 내용 | 선행 | 크기 · 시간 |
|---|---|---|---|
| K-A1 | `auth`: `SecondFactorGate` · `issueAfterSecondFactor` · `AUTH.MFA_REQUIRED` · `PasswordLoginService` 처리. `account`: `MFA_*` 이벤트 · `MailKind.MFA_NOTICE`(ko/en 골든) · `ChallengePurposes.MFA_LOGIN` · 다른 모듈이 부를 `Reauth.check(accountId, …)` 오버로드(지금은 `Account` 를 받는다 — 빈 `Reauth` 는 이미 있다). 고리가 비면 그대로라는 시험 | 스펙 승인 | M · 2h |
| K-A2 | `auth-mfa` · `auth-mfa-jdbc`: TOTP · base32 · QR · 등록/확인/재등록 · 로그인 2단계 · 복구 코드 · 한도 · 재사용 방지 · 끄기 · 상태 · 탈퇴 · 기동 검사 · V 파일 두 방언 · 모듈 문서 · 설정 yml · capabilities · CHANGELOG | K-A1 | L · 5h |
| K-A3 | 필수(`required-for` · `MfaPolicy` · 인터셉터 · 15분 면제) · 운영자 해제 · 권한 선언 · 견본 조립 · 견본 통합 시험 · `docs/sample.md` | K-A2 | M · 3h |
| R-A1 | `@skeleton/auth`: api · `mfaRequiredOf` · 2단계 화면(네 진입로) · 라우트 · 설정 칸(마법사 · 복구 코드 · 끄기) · 문구 · 스토리 + `play` | 스펙 승인(계약은 §3 — K-A2 와 나란히) | L · 5h |
| R-A2 | 등록 필수 문 화면 · 운영 해제 줄 메뉴 · 견본 조립 · e2e ①~④ · TOTP 시험 도우미 | K-A3, R-A1 | M · 3h |

**줄기 B — API 키**

| 조각 | 내용 | 선행 | 크기 · 시간 |
|---|---|---|---|
| K-B1 | `platform` `@ApiKeyScope` · `ApiKeyScopeDeclaration`. `auth` `BearerTokenAuthenticator` 고리. `api-key` · `api-key-jdbc`: 발급 · 목록 · 회수 · 범위 목록 · 요청 인증 · 인터셉터(범위 · 한도) · `ApiKeyOwnerPolicy` · 마지막 사용 · 탈퇴 · V 파일 두 방언 · 모듈 문서 · capabilities · CHANGELOG | 스펙 승인 | L · 6h |
| K-B2 | 운영 경로(목록 · 회수) · 권한 선언 · `API_KEY_*` 이벤트 · 견본 조립(범위 선언 · 노트 표시 · yml) · 견본 통합 시험 | K-B1 | M · 2.5h |
| R-B1 | `@skeleton/auth/api-keys`(`ApiKeysSection` · api · 훅) · `@skeleton/auth/admin` `AdminApiKeys` · 문구 · 스토리 + `play` | 스펙 승인(계약은 §4) | M · 4h |
| R-B2 | 견본 조립(설정 칸 · 운영 메뉴) · e2e ⑤ · ⑥ | K-B2, R-B1 | M · 2h |

**나란히 돌리는 법**: 스펙 승인 뒤 K-A1 · R-A1 · K-B1 · R-B1 넷이 동시에 출발할 수 있다. 맥 전체 무거운 검사 줄(3자리)을 생각하면 kotlin 둘 + react 하나가 현실적이다. 합계 약 32.5시간(A 18 · B 14.5, 조각 9개 = 개발자 9번).

**겹침**(같은 파일 — 뒤에 합치는 쪽이 다시 맞춘다): kotlin `account/events/AccountEvents.kt`(K-A1 · K-B2) · `capabilities.json` · `CHANGELOG.md` · `settings.gradle.kts` · `docs/modules/README.md` · `apps/sample/build.gradle.kts` · `application.yml`(A 와 B 의 모든 K 조각), `auth` 는 서로 다른 파일(A: `AuthTokenResponseFactory` · `PasswordLoginService` / B: `JwtAuthenticationFilter`). react `screens/AccountSettings.tsx` · `labels*.ts` · `admin/index.ts` · `package.json` exports · `capabilities.json` · 견본 라우트(R-A1 · R-B1 · R-A2 · R-B2).
**전체 스위트**는 줄기마다 마지막 조각 뒤 지휘 세션이 한 번.

## 정해야 할 것

1. **API 키로 닿을 수 있는 곳**
   - A. 앱 · 모듈이 `@ApiKeyScope` 로 표시한 경로만, 범위도 앱이 선언(기본 거부) **(추천)** — GitHub fine-grained · Stripe restricted 의 「필요한 것만」, OWASP BFLA. 앱이 열 때마다 표시 한 줄을 단다.
   - B. 키 = 주인 계정 그대로(사용자 경로 전부, 운영 경로만 제외), 범위는 `read` · `write` 둘 — 만들기는 싸지만 키 하나 새면 계정 설정 · 결제까지 닿는다.
   - C. A + 운영 권한(`payment.read` 등)도 범위로 고를 수 있게(발급자 권한 안에서만) — 운영 자동화 키가 생긴다. 상승 규칙 · 운영 화면이 더 붙는다(+1 조각).
2. **견본 앱의 2단계 필수 범위**(모듈 기본은 `NONE`, 형 앱의 덮어쓰기)
   - A. `OPERATORS` — 권한이 있는 계정은 필수 **(추천)** — OWASP 「관리자는 MFA 필수」를 견본이 보여 준다. 대가: 운영 e2e 전부(대시보드 · 역할 · 결제 운영 · 배지 …)의 admin 로그인 도우미가 TOTP 를 계산해야 한다(도우미 한 곳 + 전역 준비에서 admin 등록, R-A2 가 +1시간).
   - B. `NONE` — 견본은 고르는 사람만 켠다. 필수 문은 통합 시험과 스토리로만 증명된다.
   - C. `ALL` — 모든 사용자 필수. 가입 직후 바로 등록 화면, 모든 e2e 가 TOTP 를 거친다.
3. **신뢰 기기 기억(「이 기기에서 30일 묻지 않기」)을 1차에 넣을까**
   - A. 1차는 빼고 다음 판 **(추천)** — 세션(새로고침 30일)이 이미 한 기기에서 30일 다시 묻지 않게 한다. 기억은 로그아웃 뒤에만 의미가 있고, 브라우저에 오래 사는 자격을 하나 더 만든다(본문 전달이라 `localStorage` — XSS 면).
   - B. 1차에 넣는다 — 표 `mfa_trusted_devices`(해시 · 30일 · 설정에서 목록 · 해제) + 2단계 화면의 체크 상자 + 쿠키 전달 앱만 HttpOnly 쿠키. 조각 하나(M)가 늘어난다.
4. **인증 앱과 복구 코드를 모두 잃은 사용자의 길**
   - A. 운영자 해제만 **(추천)** — 복구 길이 2단계보다 약하면 안 된다(OWASP). 대가: 형이 문의를 받아 본인 확인 뒤 운영 화면에서 해제한다.
   - B. 이메일로 스스로 해제 + 대기 기간(예: 7일, 그 사이 메일 · 로그인하면 취소) — 형의 일은 없다. 대신 메일함을 가진 공격자가 7일을 기다려 2단계를 벗긴다(2단계가 「메일 + 비밀번호」로 약해진다). 조각 하나(M) 늘어난다.

## 결정됨 (2026-10-30)

1·4 는 형이 골랐고, 2·3 은 지휘 세션이 추천안으로 정했다. 이 절이 본문보다 우선한다.

1. API 키 범위 — 앱 · 모듈이 `@ApiKeyScope` 로 표시한 경로와 선언한 범위만 허용한다(기본 거부). (형)
2. 견본 앱의 2단계 필수 범위 — OPERATORS.
3. 신뢰 기기 기억 — 1차에서 뺀다.
4. 인증 앱과 복구 코드를 모두 잃었을 때 — 운영자 해제만. (형)
