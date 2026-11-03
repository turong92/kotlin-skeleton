# auth-mfa

2단계 인증(TOTP) — 로그인 비밀번호 뒤에 인증 앱의 6자리 코드를 한 번 더 받는다. **모든 로그인 길**(비밀번호 · 소셜 · 매직 링크 · 탈퇴 취소)이 지나는 `auth` 의 `AuthTokenResponseFactory.issue` 한 곳에 꽂힌다(OWASP 「모든 길에 MFA」) —
이 모듈은 `SecondFactorGate` 의 구현이다. 새로고침(`/auth/refresh`)은 묻지 않는다(이미 통과한 세션). 모듈을 안 붙인 앱의 로그인 응답은 바이트까지 그대로다.
저장은 어댑터 `auth-mfa-jdbc` 가 한다(메모리 구현 없음). **암호화 키(`crypto`)와 저장소가 없으면 기동에 실패한다** — 메시지가 `TextEncryptor` / `MfaStore` 를 적는다.

**TOTP 고정값** RFC 6238: SHA1 · 6자리 · 30초 · 비밀 160비트(RFC 4226 권장) · 앞뒤 한 걸음(±1) 허용. 설정으로 열지 않는다(Google Authenticator 호환). 비밀 표기는 base32, QR 은 서버가 만든다(`io.nayuki:qrcodegen`, MIT) —
`qr.path` 는 `<svg viewBox="0 0 size size"><path d=…/></svg>` 에 그대로 넣는 경로 문자열 하나라 프런트에 QR 라이브러리도 `innerHTML` · data URI 도 필요 없다. `otpauth://` 는 바깥 서비스로 나가지 않는다.
**저장** 비밀은 `crypto` 의 `TextEncryptor` 봉투(`aes-gcm:v1:<키 id>:…` - 키 회전 가능)로만 DB 에 간다. 복구 코드는 16자 base32(80비트, `XXXX-XXXX-XXXX-XXXX`) 10개, **SHA-256 해시**만 저장한다(엔트로피가 높아 느린 해시가 필요 없고, JWT 비밀에서 유도한 HMAC 으로 하지 않은 까닭은 비밀을 돌려도 오래 사는 복구 코드가 죽지 않게 하려는 것). 비밀 · 복구 코드 · `mfaToken` · 코드 원문은 DB · 로그 · 이벤트 어디에도 없다(시험으로 훑는다).

## 경로 (기본 `/api/v1/auth/mfa`, `skeleton.auth-mfa.http.enabled=false` 로 닫는다)

로그인한 사용자의 것은 모두 `@ApiKeyScope` 표시가 없어 **API 키로는 닿지 않는다**. 응답 본문은 다른 경로와 같은 `{"value": …}` 봉투다.

| 메서드 · 경로 | 인증 | 요청 → 응답 |
|---|---|---|
| `GET /` | 로그인 | → `{enabled, enabledAt, recoveryCodesRemaining, required}` (`required` = `MfaPolicy`) |
| `POST /totp/enrollment` | 로그인 + **다시 인증** | `{currentPassword \| confirmationCode \| socialReauth}` → `200 {secret, otpauthUri, qr{size, path}, expiresAt}` (`no-store`, 10분). 이미 켜져 있으면 재등록 - 옛 비밀은 새 것이 확인될 때까지 산다 |
| `POST /totp/enrollment/confirm` | 로그인 | `{code}` → `200 {recoveryCodes}` (`no-store`). **첫 등록**이면 10개(한 번뿐), 재등록이면 `null`(기존 코드 유지). 켤 때 **이 세션 말고 모든 세션을 닫는다**(그래서 살아 있는 세션은 모두 2단계를 지난 세션). 대기가 없거나 10분이 지났으면 `410 MFA.ENROLLMENT_EXPIRED` |
| `POST /recovery-codes` | 로그인 + **다시 인증** | 증거만 → `200 {recoveryCodes[10]}` (`no-store`, 옛 코드는 모두 무효). 꺼져 있으면 `409 MFA.NOT_ENABLED` |
| `POST /disable` | 로그인 + **다시 인증 + 지금 코드** | 증거 + `{code \| recoveryCode}` 중 **하나** → `204` (비밀 · 복구 코드 삭제 + 메일). 꺼져 있으면 409 `MFA.NOT_ENABLED`, 앱이 필수로 정한 계정이면 409 `MFA.REQUIRED_BY_POLICY`(재등록은 된다) |
| `POST /verify` | **공개** (`mfaToken` 이 자격) | `{mfaToken, code \| recoveryCode}` → 로그인과 같은 응답(`AuthTokenResponse` - 같은 토큰 · 같은 쿠키 전달). 대기는 한 번뿐 |

**로그인 흐름** 첫 단계를 통과한 계정이 켜져 있으면 `403 AUTH.MFA_REQUIRED` + `data{mfaToken, methods: ["totp", "recovery_code"], expiresAt}`(토큰 · 세션 없음, 대기 5분). → `POST /verify`.
**탈퇴 대기 계정**: 2단계가 켜져 있으면 비밀번호만으로는 `403 AUTH.ACCOUNT_DELETION_PENDING`(취소 토큰)도 받지 못한다 - 먼저 `403 AUTH.MFA_REQUIRED` → `POST /verify` 가 맞아야 그 응답(`restoreToken`)이 나오고, 토큰에 「2단계 통과」 표지가 실려 `POST /account/delete/cancel` 이 2단계를 다시 묻지 않는다. 표지 없는 토큰으로 2단계 켜진 계정을 취소하면 `410 ACCOUNT.TOKEN_INVALID` 이고 계정 상태는 그대로다.
**오류**: 틀린 코드 · 이미 쓴 걸음 `400 MFA.CODE_INVALID`(`data.attemptsLeft` - 로그인: 이 대기의 남은 시도, 그 밖: 계정 한도의 남은 수) · 대기가 없음 · 만료 · 시도 소진 `410 MFA.CHALLENGE_EXPIRED`(1단계부터 다시) ·
한도 `429 MFA.TOO_MANY_ATTEMPTS`(`Retry-After`) · 필수인데 등록 전 `403 MFA.ENROLLMENT_REQUIRED` · 증거가 틀림 `400 ACCOUNT.CURRENT_PASSWORD_INVALID` 등(`account` 의 다시 인증) · 코드를 둘 다 보내거나 안 보냄 `400`(`errors[].field=code`).

## 규칙

- **같은 코드는 두 번 통과하지 못한다**(RFC 6238 §5.2): `last_used_step` 조건부 UPDATE(`last_used_step < :step`)라 동시에 몰려도 하나만 이긴다. 더 옛 걸음도 받지 않는다. 이미 쓴 걸음은 **틀린 것으로 센다**. 등록을 확인한 코드도 그 걸음을 쓴 것으로 친다.
- **한도**: 대기 하나에 틀린 시도 5번(다 쓰면 410) · 계정마다 틀린 시도 15분에 10번 · 하루 30번(`RateLimitStore` - `redis-rate-limit` 이 있으면 Redis). **먼저 세고 맞으면 되돌린다**(맞은 시도는 안 센다, 동시에 몰려도 한도 밖은 통과하지 못한다). 로그인 · 등록 확인 · 끄기가 같은 계정 한도를 쓴다. 걸리면 429 + `Retry-After` + **창마다 메일 한 통**(「비밀번호는 맞았지만 2단계에서 막혔어요」, `MailKind.MFA_NOTICE` `change=throttled`) + `MFA_THROTTLED`. 한도에 걸린 동안은 맞는 코드도 받지 않고 대기의 시도도 깎지 않는다. 계산: 한 번 맞힐 확률 3/10⁶ × 하루 30번 × 365일 ≈ 3%/년 — 비밀번호가 이미 샌 경우의 값이고, 첫 막힘에서 주인이 메일을 받는다.
- **다시 인증**: 등록 시작 · 복구 코드 재발급 = `account` 의 `Reauth`(비밀번호 · 메일 코드 · 소셜 코드). **끄기 = 다시 인증 + 지금 코드**(인증 앱 또는 복구 코드). 15분짜리 액세스 토큰만으로 2단계를 끄거나 바꾸지 못하게. 증거는 일이 끝난 뒤에 소비된다.
- 비밀번호 재설정은 2단계를 끄지 않는다(복구 길이 MFA 를 우회하면 안 된다 - OWASP).
- **이벤트**(`account` 의 `AccountEventType`, detail 에 코드 · 비밀 없음): `MFA_ENABLED`(`via` = enrollment \| re_enrollment) · `MFA_DISABLED` · `MFA_RESET_BY_ADMIN`(`by`) · `MFA_RECOVERY_CODES_REGENERATED` · `MFA_RECOVERY_CODE_USED`(`remaining`) · `MFA_VERIFIED`(`method`) · `MFA_FAILED`(`method`) · `MFA_THROTTLED`(`window`). 메일(`MFA_NOTICE`, `vars.change`): enabled · disabled · reset · recovery_code_used · throttled — 재발급은 메일 없이 이벤트만. 계정에 이메일이 없으면 메일은 건너뛴다.
- **탈퇴**: `AccountErasureListener`(`auth-mfa`)가 비밀 · 복구 코드 · 열려 있는 로그인 대기를 지운다.
- **필수**(`required-for: NONE | OPERATORS | ALL`, 모듈 기본 NONE): 상태의 `required`, 끄기 409(`MFA.REQUIRED_BY_POLICY`, 재등록은 된다), 그리고 아래 「필수 문」. `OPERATORS` = `OperatorAccess` 권한이 하나라도 있는 계정(저장된 역할로 판정). **권한의 종류는 가리지 않는다** - 혜택형 권한(예: 견본의 `payment.exempt`)도 하나라도 있으면 필수다(면제 권한도 돈 경로라 탈취 이득이 있어 보수적으로 센다). 좁히려면 `MfaPolicy` 빈을 갈아 끼운다. 「요금제 · 역할별 필수」는 `MfaPolicy` 빈 하나로 갈아 끼운다.

## 필수 문 (등록 전 계정 막기)

필수 대상인데 등록 전인 계정도 **로그인은 된다**(그래야 등록한다). MVC 인터셉터(`EnrollmentRequiredInterceptor`, 순서 -90 - 이메일 필수 · 아이디 필수 뒤)가 허용 목록 밖의 `/api` 요청을 `403 MFA.ENROLLMENT_REQUIRED` 로 막는다. 설정이 `NONE` 이고 정책 빈도 기본 그대로면 문은 서지 않는다. 문은 경로 스위치 `http.enabled` 와 상관없이 선다(경로를 닫고 자기 컨트롤러를 써도 `required-for` 가 무시되지 않는다).
- **열린 길**: `/api/v1/auth` 아래 전부(로그인 · 로그아웃 · 이 모듈의 등록 경로) · `GET /api/v1/account/me` · `POST /api/v1/account/reauth/confirmation`(메일 코드 다시 인증) + `skeleton.auth-mfa.required.extra-exclude-paths`(`"METHOD /패턴"`, 더하기만).
- **안 거는 것**: 익명(보안 체인이 401) · 에러 디스패치 · CORS preflight · 정적 자원 · **API 키 요청**(키는 사람이 아니라 등록할 수 없다) · **break-glass 요청**(비상문이 목적이라 2단계를 걸지 않는다 - `auth` 의 `BreakGlassAuthentication` 표지, break-glass 필터만 단다. 대상 계정이 운영자여도 등록 전에 열린다). 키를 알아보는 표지는 `auth` 의 `NonInteractiveAuthentication` 인터페이스 하나다 - `api-key` 가 키 요청의 `Authentication` 에 달고 이 문이 읽는다(두 모듈은 서로를 모른다). 앱이 만든 키 같은 자격이 있으면 그 `Authentication` 이 이 인터페이스를 구현하면 된다.
- **비용**: 요청마다 계정 한 줄(기본키) + 등록 상태 한 번을 읽고 **30초 캐시**한다. 등록 · 끄기 · 운영 해제 · 탈퇴는 캐시를 바로 지운다(등록하면 곧장 풀리고, 운영 해제는 곧장 다시 걸린다). 정책을 바꾸는 쪽(역할 부여 · 회수)은 최대 30초 뒤 반영된다.
- **15분 면제**: 필수 문에 걸린 계정이 **방금 로그인한 세션**(`required.fresh-login-window`, 기본 15분, `SessionInspector`)으로 `POST /totp/enrollment` 를 하면 다시 인증을 요구하지 않는다(막아 놓고 증거를 대라고 할 수 없다 - 첫 이메일 등록의 면제와 같다). 면제는 세션에 붙는다: 옛 세션 · 세션을 읽을 수 없는 조립(JWT 만) · 이미 켜진 계정의 재등록은 해당 없다.

## 운영자 해제 (`skeleton.auth-mfa.admin.enabled`, 기본 꺼짐 → 404)

인증 앱과 복구 코드를 **모두 잃은** 사람을 위한 유일한 길이다(1차에 이메일 · SMS 복구는 없다). 기본 경로 `/api/v1/admin/mfa/accounts`. 키로는 닿지 않는다.

| 메서드 · 경로 | 권한 | 요청 → 응답 |
|---|---|---|
| `GET /{id}` | `account.read` | → `{enabled, enabledAt, recoveryCodesRemaining, required}` (그 계정의 상태 - 해제 전에 켜져 있는지 본다) |
| `POST /{id}/reset` | `mfa.reset` (**기본 역할 없음** - 전권 `ADMIN` 만, 역할 관리에서 나눠 준다) | `{reason}`(필수 · 200자 이하) → `204`. 자기 자신 `409 MFA.SELF_RESET` · 대상이 호출자보다 높은 권한(역할 부여와 같은 상승 규칙) `403 MFA.ESCALATION` · 켜져 있지 않음 `409 MFA.NOT_ENABLED` · 없는 계정 404 |

해제 = 비밀 · 복구 코드 삭제 + 열려 있는 로그인 대기 삭제 + **대상의 모든 세션 종료** + 대상에게 메일(`MFA_NOTICE` `change=reset`) + 이벤트 `MFA_RESET_BY_ADMIN`(`by` = 운영자 id) + `OperatorAuditLog`(`mfa.credential.reset`, 대상 id 만). `reason` 은 운영자가 본인 확인을 했다는 말을 적게 하려는 입력이고 **어디에도 저장되지 않는다**.
> **본인 확인 절차 없이 해제하지 말 것.** 이 경로는 2단계를 통째로 벗기므로 사회공학 공격의 표적이다(OWASP 지원 담당 경고) - 앱은 영상 통화 · 가입 때의 정보 대조 같은 확인을 운영 절차로 정하고, 이 권한을 아주 적은 사람에게만 준다. 해제된 계정이 필수 대상이면 다음 요청부터 다시 등록 문에 걸린다.

**동작 메모**: ① 로그인마다 `mfa_totp` 한 줄을 기본키로 읽는다(켜진 계정인지 판정). ② 로그인 대기는 새 표 없이 `account` 의 챌린지 표(용도 `mfa_login`, id = mfaToken 의 SHA-256)를 쓴다 - 시도 차감 · 한 번만 지우기가 이미 원자적이고 청소도 있다. 같은 계정이 여러 대기를 동시에 가질 수 있다. ③ 신뢰 기기 기억(「30일 묻지 않기」)은 1차에 없다(세션이 한 기기에서 이미 그 일을 한다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-mfa"))` |
| 함께 오는 모듈 | `account`, `auth`, `crypto`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-mfa` — [docs/config/modules/auth-mfa.yml](../config/modules/auth-mfa.yml) |
| 기본 동작 | 켜짐. 경로 켜짐, 필수 없음(`NONE`), 운영자 해제 꺼짐, 대기 5분 · 5번, 계정 한도 15분 10번 · 하루 30번. |
| 부팅에 필요한 것 | 암호화 키(`skeleton.crypto.primary-key-id` + `skeleton.crypto.keys.<id>` → `TextEncryptor`)와 저장소 `MfaStore`(`auth-mfa-jdbc`) - 없으면 이름을 적고 기동 실패. `account`(다시 인증 · 대기 · 이벤트 · 메일)와 `auth`. |
| 교체 지점 | `MfaStore`, `MfaPolicy`, `MfaService`, `mfaSecondFactorGate`, `MfaController`, `MfaAdminController`, `mfaAccountErasureListener` |
| 마이그레이션 | 없음 (스키마는 `auth-mfa-jdbc`) |
| 프론트 짝 | 없음 (react-skeleton 쪽 화면은 따로 맞춘다) |
| 테스트 | `modules/auth-mfa/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
