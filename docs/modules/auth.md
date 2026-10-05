# auth

인증 계약과 로그인 흐름이다: 비밀번호 로그인, JWT 발급 · 검증, 로컬 `dev-login`, 비상용 `break-glass` 헤더, `SecurityFilterChain`.
계정 저장소(`AuthAccountRepository`)는 앱이 정한다. 기본 구현은 로컬 시드 사용자뿐이라 운영 프로필에서는 앱이 반드시 대체한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth` — [docs/config/modules/auth.yml](../config/modules/auth.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `local` · `dev` · `test` · 프로필 없음: 없음. `prod` · `staging`(`skeleton.auth.protected-profiles`): `skeleton.auth.jwt.secret` 32바이트 이상과 앱의 `AuthAccountRepository` 빈이 없으면 시작에 실패한다. |
| 교체 지점 | `AuthAccountRepository`, `PasswordEncoder`, `JwtTokenService`, `AuthTokenResponseFactory`, `AuthErrorWriter`, `SecurityFilterChain` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
