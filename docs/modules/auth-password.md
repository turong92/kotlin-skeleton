# auth-password

**비밀번호 로그인** `POST /api/v1/auth/login` — 이메일 또는 로그인 아이디 + 비밀번호로 토큰을 받는다(`PasswordLoginService` · `PasswordLoginController` · 입력 검증 `RequiredLoginIdentifier`).
`auth` 가 주는 계약(`AuthAccountRepository` · `AuthTokenResponseFactory` · `LoginHooks`)만 쓴다. 한도 · 기록은 `LoginHooks` 로 붙는다 — 구현은 `account-password` 의 `LoginThrottle` · `LoginRecorder` 이고 두 모듈은 서로를 모른다.
**`account-password` 와 같이 둔다 · 같이 뺀다**: 이 모듈만 두면 한도가 없어 무제한 추측이 가능하고, `account-password` 만 두면 `/auth/methods` 는 비밀번호를 알리는데 로그인 경로가 없다.
이 모듈을 빼도 `auth` 에는 `LoginHooks` · `PasswordEncoder` 기본(BCrypt, 시드 계정 해시용) · `InvalidCredentialsException` · `AuthTokenResponse` 가 남는다(소셜 · 링크가 쓴다).

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `POST /api/v1/auth/login` | `{email 또는 username, password}` → 토큰 응답. 틀리면 `401 AUTH.INVALID_CREDENTIALS`, 한도를 넘으면 `429 AUTH.TOO_MANY_ATTEMPTS`, 정지 · 이메일 미확인 · 2단계는 비밀번호가 맞은 뒤 403 | 공개 |

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-password"))` |
| 함께 오는 모듈 | `auth`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 — 한도는 account-password 가 읽는 account 의 login 아래 설정 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `auth` 의 부팅 조건과 같다. 따로 없다. |
| 교체 지점 | `PasswordLoginService`, `PasswordLoginController` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-password/src/test`, 진짜 한도 · 기록과 이은 조합은 `apps/sample/src/test` 의 `PasswordLoginHooksIntegrationTest` |

[auth](auth.md) · [account-password](account-password.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
