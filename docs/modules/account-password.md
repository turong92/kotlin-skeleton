# account-password

`account` 위에 얹는 **이메일 + 비밀번호** 수단 — 가입 · 이메일 코드 확인, 비밀번호 찾기 · 재설정 · 변경, 비밀번호 정책 · 해시, **로그인 시도 제한 · 로그인 결과 기록**.
`account` 는 이 모듈을 모른다: `PasswordCredentials`(해시 만들기 · 맞는지 보기) · `ResetLinkIssuer`(재설정 링크) 두 포트와 `AccountCore.passwords` · `resetLinks` 공급자만 가지고, 이 모듈이 빈으로 채운다.
소셜 · 링크만 쓰는 서비스는 이 모듈을 빼면 비밀번호 경로가 하나도 남지 않는다(`/auth/methods` 의 `signUp.password` 도 꺼진다, 다시 인증은 코드 · 소셜로). **`auth-password` 와 같이 둔다 · 같이 뺀다** — 한도 · 기록 고리(`LoginThrottle` · `LoginRecorder`)는 여기, `POST /auth/login` 은 거기이고 둘은 auth 의 `LoginHooks` 계약으로만 이어진다.
설정은 그대로 `skeleton.account.*`(`AccountProperties` 는 `account` 에 있다 — 와이어 호환), 에러 코드 · 경로 · `ReauthInput.currentPassword` 도 그대로다. 시드 계정에 비밀번호가 있는데 이 모듈이 없으면 **기동 실패**다.

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `POST /api/v1/account/sign-up` | 가입 시도 — **늘 202 + `signUpId` + `expiresAt` · `resendAvailableAt`**(있는 주소여도 같은 응답, 계정은 아직 없다) | 공개 |
| `POST /api/v1/account/verification/resend` · `POST /api/v1/auth/verify-email` | 같은 시도의 새 코드 · `{signUpId, code}` 로 확인 — 계정을 만들고 **로그인(토큰)** | 공개 |
| `POST /api/v1/account/password/forgot` · `…/reset` · `GET …/policy` | 재설정 메일(늘 202) · 새 비밀번호(모든 세션 종료) · 정책 힌트 | 공개 |
| `POST /api/v1/account/password/change` | 현재 비밀번호 필요(없는 계정은 첫 비밀번호 설정). 다른 세션 종료 | 로그인 |

로그인 한도는 IP 와 입력 식별자(이메일 · 아이디)마다 창 안의 시도 수를 센다(성공도 센다, 존재 여부가 드러나지 않게 — [docs/accounts.md](../accounts.md) 위협 모델). 결과 기록은 성공이면 `lastLoginAt` · `LOGIN_SUCCESS`, 실패면 `LOGIN_FAILURE`. 해시는 `{id}` 접두사 인코더(기본 bcrypt, argon2 는 opt-in — 접두사 없는 옛 bcrypt 행도 받고 로그인 때 새 방식으로 올린다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:account-password"))` |
| 함께 오는 모듈 | `account`, `auth`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 — account 의 접두사 그대로(sign-up · verification · reset · login · password 아래): [docs/config/modules/account.yml](../config/modules/account.yml) |
| 기본 동작 | 켜짐. 가입 · 확인 · 재설정 · 변경 HTTP 와 로그인 시도 제한 · 기록이 켜진다. |
| 부팅에 필요한 것 | `account` 의 부팅 조건과 같다(메일 발송기 등). 따로 없다. |
| 교체 지점 | `PasswordEncoder`, `PasswordHasher`, `PasswordPolicy`, `BreachedPasswordCheck`, `PasswordSignInMethod`, `ResetLinkIssuer`, `RegistrationService`, `PasswordService`, `LoginThrottle`, `LoginRecorder`, `AccountPasswordPublicController`, `AccountPasswordController` |
| 마이그레이션 | 없음 (해시는 `account_identities.secret` 의 `method='password'` 행 — 스키마는 `account-jdbc`) |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/account-password/src/test` |

자세히: [계정 수명주기 · 위협 모델](../accounts.md) · [account](account.md) · [auth-password](auth-password.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
