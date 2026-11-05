# auth-social-naver

Naver OAuth 토큰 교환 · 프로필 조회 HTTP 클라이언트를 `auth-social` 의 제공자로 등록한다.
클라이언트 id · secret 만 있으면 된다. 별도 설정 접두사는 없고 `auth-social` 블록의 `providers` 아래에 둔다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social-naver"))` |
| 함께 오는 모듈 | `platform`, `auth-social`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 `auth-social` 블록의 providers.naver 아래) — [auth-social 블록](../config/modules/auth-social.yml) |
| 기본 동작 | 꺼짐. `skeleton.auth-social.providers.naver.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 없음. 클라이언트 id 는 켤 때 필요하다. secret 은 웹 리다이렉트(인가 코드) 흐름에만 쓴다 — 앱 토큰(프로필 API 확인)만 쓰면 비워도 된다(코드 로그인 · 코드 연결은 404, `GET /auth/methods` 는 `clientId` · `redirectUri` 를 비운다). |
| 토큰 요청의 `state` | 로그인 · 연결 · 다시 인증 요청의 선택 필드 `state`(콜백이 받은 값)를 `OAuthCodeExchange.state` 로 받아 토큰 요청 폼에 싣는다. 없으면 싣지 않는다 (docs/real-provider-setup.md §4) |
| 앱 SDK 토큰 | `POST /auth/social/naver/token` 은 모듈이 켜져 있으면 열린다. 앱이 준 액세스 토큰을 프로필 API(`/v1/nid/me`)로 확인해 코드 흐름과 같은 프로필을 만든다(401 이면 401 `AUTH.SOCIAL_ACCESS_TOKEN_INVALID`, 장애는 502). 앱별 id 여부는 확인 안 됨 - 다른 앱이 발급한 토큰인지는 가려내지 못한다 |
| 교체 지점 | `NaverOAuthProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-social-naver/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
