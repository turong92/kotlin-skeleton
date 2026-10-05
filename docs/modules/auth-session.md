# auth-session

로그인에 **리프레시 토큰**을 더한다: 불투명 난수(해시만 저장) · **매 새로고침마다 회전** · 이미 쓴 토큰이 다시 오면 **재사용 탐지로 세션 전체를 닫는다** · 기기 · IP · 마지막 사용을 보여 주는 **세션 목록** · 하나/전부 철회 · 로그아웃.
액세스 토큰은 그대로 JWT(15분)이고 `sid` 클레임으로 세션을 가리킨다. `auth` 만 있으면 지금까지처럼 액세스 토큰만 나가고, 이 모듈을 더하면 어떤 로그인 방법이든(`AuthTokenResponseFactory` 를 지나는 모든 것) 세션이 열린다.
전달 방식 기본은 **body**(응답 JSON ↔ 요청 본문 — 쿠키가 없어 CSRF 면이 없다). `cookie` 는 HttpOnly · Secure · SameSite=Strict + CSRF 헤더를 요구한다 ([docs/accounts.md](../accounts.md) CSRF).

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `POST /api/v1/auth/refresh` | 리프레시 토큰 → 새 액세스 토큰 + 회전된 리프레시 토큰 (`AUTH.REFRESH_INVALID` · `AUTH.REFRESH_REUSED`) | 토큰이 곧 자격 |
| `POST /api/v1/auth/logout` | 이 세션 종료 (늘 204) | 공개 |
| `GET /api/v1/auth/sessions` · `DELETE …/{id}` · `DELETE …?keepCurrent=true` | 세션 목록(현재 표시) · 하나 철회 · 전부 철회 | 로그인 |

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-session"))` |
| 함께 오는 모듈 | `platform`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-session` — [docs/config/modules/auth-session.yml](../config/modules/auth-session.yml) |
| 기본 동작 | 켜짐. body 전달, 절대 30일 · 유휴 14일, 유예 없음, 저장은 메모리(로컬) — 운영은 `auth-session-jdbc`. |
| 부팅에 필요한 것 | 로컬 · 시험: 없음. stage · prod: 메모리 저장소가 아닌 `SessionStore`(`auth-session-jdbc`) · 쿠키 전달이면 `cookie.secure=true` — 아니면 `DeployGuard`(`auth-session`)가 막는다. |
| 교체 지점 | `SessionStore`, `SessionService`, `RefreshTokenDelivery`, `SessionClients`, `LoginSessionIssuer`, `SessionRevoker`, `SessionController`, `AuthSessionDeployGuard` |
| 마이그레이션 | 없음 (스키마는 `auth-session-jdbc`) |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-session/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
