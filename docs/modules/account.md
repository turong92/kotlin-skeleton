# account

계정 수명주기 전체를 캡슐화한 모듈: **계정 · 로그인 수단(identities) · 역할**, 이메일+비밀번호 **가입 · 이메일 코드 확인**(가입 시도에 묶인 6자리 코드 — 확인이 곧 로그인), **비밀번호 재설정 · 변경**, **이메일 변경**(새 주소로 간 6자리 코드를 요청한 세션에서 입력한 뒤에만 바뀐다),
**소셜 연결 · 해제**, **삭제**(다시 인증 → 유예 → 지우기, 다른 모듈의 데이터는 `AccountErasureListener` 로), **로그인 시도 제한 · 캡차 고리 · 계정 이벤트 · 경보**, **운영자 도구**(정지 · 역할 · 첫 관리자)와 HTTP.
`auth` 의 `AuthAccountRepository` 포트를 이 모듈이 진짜 계정으로 구현하므로 `auth` 는 그대로(의존성 한 줄) 실제 계정으로 로그인한다.
저장은 어댑터 `account-jdbc`(PostgreSQL · MySQL)가 한다 — 이 모듈의 메모리 저장소는 로컬 · 시험용이다(stage · prod 가드가 막는다).

**로그인 수단 추상화**: 계정에는 수단이 없고 `identities` 행(method 문자열 코드 + subject)이 붙는다. 이메일+비밀번호 · 각 소셜 제공자 · 매직 링크가 같은 표의 행이다.
새 수단은 `SignInMethod` 빈 하나 + 자기 엔드포인트에서 `AccountSignInService.signIn(SignInProof)` 호출 + `AuthTokenResponseFactory.issue` — 스키마 · 이 모듈은 그대로 ([docs/accounts.md](../accounts.md) "새 로그인 수단 더하기").

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `POST /api/v1/account/sign-up` | 가입 시도 — **늘 202 + `signUpId`** (있는 주소여도 같은 응답, 계정은 아직 없다) | 공개 |
| `POST /api/v1/account/verification/resend` · `POST /api/v1/auth/verify-email` | 같은 시도의 새 코드 · `{signUpId, code}` 로 확인 — 계정을 만들고 **로그인(토큰)** | 공개 |
| `POST /api/v1/account/password/forgot` · `…/reset` · `GET …/policy` | 재설정 메일(늘 202) · 새 비밀번호(모든 세션 종료) · 정책 힌트 | 공개 |
| `POST /api/v1/account/password/change` | 현재 비밀번호 필요(없는 계정은 첫 비밀번호 설정). 다른 세션 종료 | 로그인 |
| `POST /api/v1/account/email/change` · `POST /api/v1/account/email/change/confirm` | 새 주소로 코드(202) · 같은 세션에서 코드 입력하면 바뀜(옛 주소에 알림) | 로그인 |
| `GET` · `PATCH /api/v1/account/me` | 프로필(이름 · 로케일 · 시간대) · 로그인 수단 목록 | 로그인 |
| `GET /api/v1/account/identities` · `DELETE …/{id}` · `POST …/social/{provider}` | 수단 목록 · 해제(다시 인증, 마지막은 409) · 소셜 연결(`auth-social` 이 있을 때) | 로그인 |
| `POST /api/v1/account/reauth/confirmation` | 비밀번호 없는 계정의 다시 인증 코드(6자리, 이 세션에서만) | 로그인 |
| `POST /api/v1/account/delete/confirmation` · `POST /api/v1/account/delete` | 삭제 확인 코드(비밀번호 없는 계정) · 삭제 예약(202, 다시 인증) | 로그인 |
| `/api/v1/admin/accounts` … | 목록 · 정지 · 복구 · 역할 부여/회수 (`skeleton.account.admin.enabled=true`) | 관리자 역할 |

에러 코드는 `ACCOUNT.*` · `AUTH.*` (표: [docs/accounts.md](../accounts.md)). 계약 전체: 같은 문서의 "HTTP 계약".

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:account"))` |
| 함께 오는 모듈 | `platform`, `auth` |
| 컴파일 전용 | `notification-mail`, `captcha-turnstile`, `alert`, `idempotency`, `auth-social`, `job-queue-jdbc` |
| 설정 접두사 | `skeleton.account` — [docs/config/modules/account.yml](../config/modules/account.yml) |
| 기본 동작 | 켜짐. 가입 · 확인 · 재설정 · 변경 · 삭제 HTTP 와 로그인 시도 제한이 켜지고, 관리자 HTTP · 소셜 가입 · 병합 · 시드 계정 · 첫 관리자 · 링크 로그는 꺼져 있다. 저장은 메모리(로컬) — 운영은 `account-jdbc`. 메일 모듈이 없으면 보내지 않고 알린다. |
| 부팅에 필요한 것 | 로컬 · 시험: 없음. stage · prod(`skeleton.env` 또는 auth 의 보호 프로필): 저장소 · 토큰 저장소 · 챌린지 저장소(`account-jdbc`), 메일 발송기(`notification-mail`), `skeleton.account.mail.link-base-url` — 없으면 `DeployGuard`(`account`)가 기동을 막고 시드 계정 · 링크 로그 켬도 막는다. |
| 교체 지점 | `AccountRepository`, `OneTimeTokenStore`, `ChallengeStore`, `CodeHasher`, `SocialReauthVerifier`, `PasswordPolicy`, `BreachedPasswordCheck`, `PasswordEncoder`, `AccountMailTemplates`, `AccountMailTransport`, `AccountMailer`, `AccountTaskRunner`, `AccountCaptcha`, `AccountEventPublisher`, `AccountEventListener`, `SignInMethod`, `AccountCallers`, `AccountPublicController`, `AccountController`, `AdminAccountController`, `AccountDeployGuard` |
| 마이그레이션 | 없음 (스키마는 `account-jdbc`) |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/account/src/test`, `modules/account/src/noOptionalTest` (선택 통합이 클래스패스에 없을 때) |

자세히: [계정 수명주기 · 위협 모델](../accounts.md) · [auth](auth.md) · [account-jdbc](account-jdbc.md) · [auth-session](auth-session.md) · 메일 링크 로그인은 `auth-magic-link` 모듈 (모듈 색인)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
