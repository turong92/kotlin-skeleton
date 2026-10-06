# auth-social-google

Google OAuth 토큰 교환 · 프로필 조회 HTTP 클라이언트를 `auth-social` 의 제공자로 등록한다.
클라이언트 id · secret 만 있으면 된다. 별도 설정 접두사는 없고 `auth-social` 블록의 `providers` 아래에 둔다.

**PKCE**: `pkce=SUPPORTED` — 프론트가 `code_challenge`(S256)를 보냈다면 `codeVerifier` 를 `code_verifier` 로 토큰 엔드포인트에 넘긴다(`client_secret` 은 그대로 필요하다). Google 의 discovery 문서는 `code_challenge_methods_supported: ["plain","S256"]` 를 싣지만
([OpenID Connect 문서](https://developers.google.com/identity/openid-connect/openid-connect)) **웹 서버 앱 문서([web-server](https://developers.google.com/identity/protocols/oauth2/web-server))에는 PKCE 파라미터 설명이 없다 — 웹 클라이언트 + client secret 조합에서 Google 이 검증기를 실제로 강제하는지는 확인 필요**(내일 실제 앱으로 `code_challenge` 를 보낸 흐름과 안 보낸 흐름을 둘 다 확인한다). SUPPORTED 라서 어느 쪽이든 동작한다.
`email_verified` 클레임은 문서대로 "사용자의 이메일이 확인되었는가" 이고 그대로 `emailVerified` 가 된다. 콘솔: [Google Cloud Console](https://console.cloud.google.com/apis/credentials) → OAuth 클라이언트 ID(웹 애플리케이션) → **승인된 리디렉션 URI**: 로컬 `http://localhost:5173/auth/callback`, 배포 `https://<도메인>/auth/callback` (정확 일치).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social-google"))` |
| 함께 오는 모듈 | `platform`, `auth-social`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 `auth-social` 블록의 providers.google 아래) — [auth-social 블록](../config/modules/auth-social.yml) |
| 기본 동작 | 꺼짐. `skeleton.auth-social.providers.google.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 없음. 클라이언트 id · secret 은 켤 때 필요하다. |
| 교체 지점 | `GoogleOAuthProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-social-google/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
