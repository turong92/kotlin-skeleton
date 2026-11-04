# auth-social-apple

**Apple 로그인(iOS 앱)** — 앱의 네이티브 SDK(Sign in with Apple)가 준 `identityToken` 또는 `authorizationCode` 를 서버가 검증하고, 탈퇴 때 애플의 사용자 토큰을 철회한다.
`skeleton.auth-social-apple.client-id` 가 있을 때만 켜진다 (별도 스위치 없음). 로그인 수단 이름 `apple`. **웹 리다이렉트는 지원하지 않는다**(`GET /auth/methods` 에 인가 URL 이 없고 `nativeToken=id_token` 만 있다) — 안드로이드 · 웹 애플은 Services ID 와 `form_post` 콜백이 따로 필요해 다음 단계다.

| 하는 일 | 값 · 근거 |
|---|---|
| 앱의 로그인 | `POST /api/v1/auth/social/apple/token` `{ "idToken", "nonce", "displayName" }` ([auth-social](auth-social.md) 의 토큰 교환 경로) |
| ID 토큰 검증 | 키 `https://appleid.apple.com/auth/keys`(JWKS, 1시간 보관 · 모르는 `kid` 는 30초 안에 한 번만 다시 받음), **RS256 만**(none · HS · ES 는 서명을 보기 전에 거절), `iss`=`https://appleid.apple.com`, `aud`=`client-id`(번들 id), `exp`(+60초) |
| nonce | **필수**. 앱이 만든 원문을 `nonce` 로 보내고(8–256자), SDK 에는 `sha256(원문)` 의 hex 를 준다. 서버는 토큰의 `nonce` 클레임이 `SHA-256 hex(요청 원문)` 인지 본다 — 없으면 400 `AUTH.SOCIAL_NONCE_FAILED`, 다르면 401 `AUTH.SOCIAL_ID_TOKEN_INVALID` |
| 프로필 | `sub`(= 계정의 제공자 주체) · `email` · `email_verified`(불리언 또는 문자열 `"true"`) · `is_private_email`. **이름은 토큰에 없다** — 애플은 첫 로그인 때 SDK 응답에만 이름을 주므로 앱이 요청의 `displayName` 으로 넘긴다. 새 계정의 닉네임 후보로만 쓴다 |
| 이메일의 권위 | `emailVerified`=`email_verified`. `emailAuthoritative`(병합 · 새 계정 주소에 쓰는 것)는 **`privaterelay.appleid.com` · `icloud.com` · `me.com` · `mac.com`** 일 때만 true. 애플 ID 에 붙은 남의 도메인 주소는 주인이 바뀌어도 "확인됨" 으로 남으므로 믿지 않는다 |
| 인가 코드 | 서버가 `POST {base-url}/auth/token`(`client_id`=번들 id, `client_secret`=아래 JWT) 으로 교환하고 응답의 `id_token` 을 위와 같이 검증한다. 코드 흐름 `POST /api/v1/auth/social/apple/login` 도 같은 모듈로 돈다(nonce 필수) |
| client_secret JWT | ES256, 헤더 `kid`=`key-id`, `iss`=`team-id`, `sub`=`client-id`, `aud`=`https://appleid.apple.com`, `iat`=지금, `exp`=지금+5분. **부를 때마다 새로 만든다**(캐시 없음 — 6개월 만료를 챙길 일이 없다). 키는 기동 때 한 번 읽는다 — 틀리면 기동 실패하고 메시지에 키 값은 없다 |
| 탈퇴 철회 | `POST /api/v1/auth/social/apple/revoke`(**로그인 필요**) `{ "authorizationCode" }` → 코드를 교환해 refresh token 을 얻고 `POST {base-url}/auth/revoke`(`token`, `token_type_hint=refresh_token`) → `204`. 코드를 애플이 거절하면 401 `AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE`, 애플 장애 · 우리 설정 거절은 502 `AUTH_SOCIAL.PROVIDER_GATEWAY_ERROR`, 이 프로젝트에서 애플이 꺼져 있으면 404. OpenAPI 에 보인다 |

**탈퇴 순서(앱이 정한다)**: 탈퇴 직전에 애플 시트를 한 번 더 띄워 `authorizationCode` 를 받고 → `POST /api/v1/auth/social/apple/revoke` → `POST /api/v1/account/delete`. 철회만 되고 탈퇴가 실패해도 해가 없다(다음 애플 로그인에서 다시 동의하고 같은 `sub` 로 들어온다). 근거: App Store 는 Sign in with Apple 을 쓰는 앱이 탈퇴할 때 REST API 로 사용자 토큰을 철회하도록 요구한다.

**이메일 숨기기를 고른 사용자**는 릴레이 주소(`…@privaterelay.appleid.com`)를 갖는다. 우리 메일(가입 코드 · 재설정 · 탈퇴 확인 코드)이 닿으려면 애플 개발자 콘솔 "Sign in with Apple for Email Communication" 에 **보내는 도메인을 등록**해야 한다. 안 하면 그 계정은 이메일로 다시 인증할 수 없어 탈퇴가 막힌다.

## 설정 (환경변수)

`SKELETON_AUTH_SOCIAL_APPLE_CLIENT_ID`(env:, 번들 id) · `SKELETON_AUTH_SOCIAL_APPLE_TEAM_ID`(env:) · `SKELETON_AUTH_SOCIAL_APPLE_KEY_ID`(env:) · `SKELETON_AUTH_SOCIAL_APPLE_PRIVATE_KEY`(**운영자 비밀** — `.p8` 파일 내용 전체. 줄바꿈이 `\n` 두 글자로 와도 된다). 설정은 Map 이 아니라 평범한 속성 클래스라 별칭 없이 그대로 묶인다(`AppleAutoConfigurationTest`). 프로젝트 모드는 `skeleton.projects.<id>.config.auth-social-apple.*` — 번들 id · team · key · 개인키는 프로젝트 자기 값만 읽는다(공통 값으로 내려가지 않는다). 철회 경로는 프로젝트 모드에서 항상 있고, 애플을 안 켠 프로젝트는 404 다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social-apple"))` |
| 함께 오는 모듈 | `platform`, `auth-social`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-social-apple` — [docs/config/modules/auth-social-apple.yml](../config/modules/auth-social-apple.yml) |
| 기본 동작 | 꺼짐. `client-id` 를 적으면 켜진다 (별도 `enabled` 없음). |
| 부팅에 필요한 것 | 없음. 켠 뒤에는 `team-id` · `key-id` · `private-key`(PKCS#8 PEM, P-256)가 있어야 한다 (없거나 틀리면 기동 실패: `skeleton.auth-social-apple.<키> must not be blank`). |
| 교체 지점 | `AppleOAuthProvider`, `AppleRevokeController` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 (앱은 네이티브 SDK 로 토큰을 받아 토큰 교환 경로로 보낸다 - react 패키지는 없다) |
| 테스트 | `modules/auth-social-apple/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
