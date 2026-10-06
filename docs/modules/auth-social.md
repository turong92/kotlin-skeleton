# auth-social

소셜 로그인의 제공자 중립 계약(`OAuthProviderRegistry`, 계정 연결, 가입 정책)과 `/auth/social/*` 라우팅이다.
제공자(Google · Kakao · Naver · X · 범용 OIDC/LINE)는 각자의 모듈이 등록한다. 제공자 모듈이 없으면 라우트는 있어도 쓸 수 있는 제공자가 없다.

`OAuthUserProfile.emailVerified` 는 제공자가 이메일을 **확인했다고** 알려 준 경우만 true 다 (Google `email_verified` · Kakao `is_email_verified`; Naver 는 알리지 않아 false). `account` 가 있으면 연결 표 · 가입 정책이 계정의 로그인 수단 표로 바뀌고,
확인된 이메일이 기존 계정과 겹쳐도 기본은 병합하지 않는다 ([계정 수명주기](../accounts.md)).

## PKCE · nonce (제공자별 정책)

제공자는 `pkce`(`REQUIRED` | `SUPPORTED` | `UNSUPPORTED`)와 `nonce`(같은 값 집합)를 선언한다. 로그인 · 연결 · `socialReauth` 요청은 `codeVerifier` · `nonce` 를 함께 보낼 수 있고,
서버는 **제공자를 부르기 전에** 정책을 확인한다 — REQUIRED 인데 없으면 `400 AUTH.SOCIAL_PKCE_FAILED` / `AUTH.SOCIAL_NONCE_FAILED`(한 번 쓰는 인가 코드와 다시 인증 증거가 타지 않는다), UNSUPPORTED 면 버리고, 검증기는 RFC 7636 모양(43–128자 `[A-Za-z0-9-._~]`)이어야 한다.
`GET /auth/methods` 가 제공자별 `pkce` · `nonce` · `authorize{url, scopes, params}` 를 내려 프론트가 인가 URL 을 하드코딩하지 않는다. 전체 계약: [account-http-contract.md](../account-http-contract.md) 끝의 "FINAL-3 + social PKCE".
제공자 모듈은 `OAuthProvider.pkce` · `nonce` · `authorize` · `fetchProfile(OAuthCodeExchange)` 를 구현하면 되고(기본은 UNSUPPORTED · 옛 두 인자 메서드), `autoEnabled=true` 면 `providers.<id>.enabled` 없이 빈이 있다는 것만으로 켜진다 (`auth-social-oidc` · `auth-social-x` 는 client id 가 스위치다).
`OAuthUserProfile.avatarUrl` 은 제공자가 준 https 사진 주소다 — `account` 는 아직 저장하지 않는다.
토큰 엔드포인트 오류의 갈래(코드 틀림 401 · 검증기 틀림 400 · 우리 client 설정 거부 502 · 한도 · 장애 502)는 `OAuthTokenErrors`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social"))` |
| 함께 오는 모듈 | `platform`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-social` — [docs/config/modules/auth-social.yml](../config/modules/auth-social.yml) |
| 기본 동작 | 켜짐. 환경변수 `<P>_AUTH_SOCIAL_PROVIDERS_<X>_CLIENT_ID` 같은 이름은 `ProviderEnvironmentAliases`(소셜 제공자 환경변수 규칙 하나 — `auth-social-oidc` 도 같은 구현, 제공자 하나짜리 `auth-social-x` 는 별칭 없이 그대로 묶인다)가 점 표기 프로퍼티로 옮겨 준다(Map 값이라 스프링 느슨한 바인딩만으로는 묶이지 않는다). 토큰 교환 거절은 제공자 `error` 코드와 함께 로그에 남는다. 제공자가 켜지기 전에는 로그인 가능한 제공자가 없다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `OAuthProviderRegistry`, `OAuthAccountLinkRepository`, `OAuthAccountProvisioningPolicy`, `OAuthSocialLoginService` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-social/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
