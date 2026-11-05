# auth-social-kakao

Kakao OAuth 토큰 교환 · 프로필 조회 HTTP 클라이언트를 `auth-social` 의 제공자로 등록한다.
클라이언트 id · secret 만 있으면 된다. 별도 설정 접두사는 없고 `auth-social` 블록의 `providers` 아래에 둔다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social-kakao"))` |
| 함께 오는 모듈 | `platform`, `auth-social`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 `auth-social` 블록의 providers.kakao 아래) — [auth-social 블록](../config/modules/auth-social.yml) |
| 기본 동작 | 꺼짐. `skeleton.auth-social.providers.kakao.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 없음. 클라이언트 id 는 켤 때 필요하다. secret 은 웹 리다이렉트(인가 코드) 흐름에만 쓴다 — 앱 토큰(`app-id`)만 쓰면 비워도 된다(코드 로그인 · 코드 연결은 404, `GET /auth/methods` 는 `clientId` · `redirectUri` 를 비운다). |
| 앱 SDK 토큰 | `app-id`(콘솔의 "앱 ID" 숫자, 환경변수 `SKELETON_AUTH_SOCIAL_PROVIDERS_KAKAO_APP_ID`)가 있을 때만 `POST /auth/social/kakao/token` 이 열린다(없으면 404). 액세스 토큰을 `GET /v1/user/access_token_info` 로 확인해 `app_id` 가 설정과 다르면 401 `AUTH.SOCIAL_ACCESS_TOKEN_INVALID`, 카카오가 401 이어도 같은 401, 카카오 장애는 502 (docs/real-provider-setup.md §4) |
| 교체 지점 | `KakaoOAuthProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-social-kakao/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
