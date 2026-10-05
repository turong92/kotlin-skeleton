# auth

인증 계약과 로그인 흐름이다: 비밀번호 로그인, JWT 발급 · 검증, 로컬 `dev-login`, 비상용 `break-glass` 헤더, `SecurityFilterChain`.
계정 저장소(`AuthAccountRepository`)는 앱이 정한다. 기본 구현은 로컬 시드 사용자뿐이라 운영 프로필에서는 앱이 반드시 대체한다.

**계정 · 세션을 얹는 고리** (모두 선택 — `auth` 만 쓰면 그대로다): `LoginHooks`(비밀번호 로그인 앞뒤 — `account` 가 한도 · 이벤트를 건다), `LoginSessionIssuer`/`SessionRevoker`(`auth-session` 이 구현 — 어떤 로그인 방법이든 세션이 열린다),
`AuthAccount.loginBlock`(이메일 미확인 · 정지는 비밀번호가 맞은 뒤에 403), `upgradePasswordHash`(로그인 때 해시를 새 방식으로), 계정이 없어도 해시 비교를 한 번 한다(응답 시간으로 존재가 드러나지 않게). 진짜 계정: [account](account.md) · [계정 수명주기](../accounts.md).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth` — [docs/config/modules/auth.yml](../config/modules/auth.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `local` · `dev` · `test` · 프로필 없음: 없음. `prod` · `staging`(`skeleton.auth.protected-profiles`): `skeleton.auth.jwt.secret` 32바이트 이상과 앱의 `AuthAccountRepository` 빈이 없으면 시작에 실패한다. `skeleton.env=stage|prod`(옵트인 — [배포](../deploy.md))이면 프로필 없이도 같다. 규칙은 `DeployGuard`(`auth`)로도 보인다. |
| 교체 지점 | `AuthAccountRepository`, `PasswordLoginService`, `LoginHooks`, `LoginSessionIssuer`, `SessionRevoker`, `PasswordEncoder`, `JwtTokenService`, `AuthTokenResponseFactory`, `AuthErrorWriter`, `SecurityFilterChain`, `AuthDeployGuard` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
