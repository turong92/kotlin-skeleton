# 앱 소셜 로그인(네이티브 SDK 토큰 교환) 설계

날짜 2026-11-04. 대상: kotlin-skeleton(main `11987e2a`) · flutter-skeleton(main `feb3276`). 이 문서는 설계만 담는다. 두 레포는 고치지 않았다.

형 결정: 업계 관행을 따른다. 앱은 네이티브 SDK로 토큰을 받고, 서버가 그 토큰을 검증한 뒤 우리 세션을 준다. 애플 로그인을 넣는다. 공개 스켈레톤 규칙을 지킨다(조립식, 기본 꺼짐, 앱 yml이 켬, 쓸데없는 옵션 금지, 예시는 `example.com`, 키는 시험용 가짜만).

## 0. 왜 필요한가

- 지금 서버의 소셜 로그인은 **인가 코드만** 받는다(`OAuthSocialAuthController` → `OAuthProvider.fetchProfile(OAuthCodeExchange)`). 네이티브 SDK는 코드 대신 토큰을 준다. 구글은 `idToken`, 애플은 `identityToken`, 카카오와 네이버는 `accessToken`이다. 그래서 앱에서 소셜 로그인을 쓸 수 없다.
- 계정 쪽도 코드만 받는다. 소셜 연결(`POST /account/identities/social/{p}`)과 다시 인증(`socialReauth`)이 그렇다. 이메일 없는 계정(네이버처럼)은 탈퇴할 때 `socialReauth`가 필요하다. 앱이 이걸 못 하면 앱 안에서 탈퇴를 못 한다. 이건 App Store 5.1.1(v) 위반이다("If your app supports account creation, you must also offer account deletion within the app").
- **애플이 필요한 근거**(App Review Guidelines 4.8, 2026-11-04 원문 확인): "Apps that use a third-party or social login service (such as … Google Sign-In …) to set up or authenticate the user's primary account with the app must also offer as an equivalent option another login service" — 조건: 이름 · 이메일만 수집, 이메일 숨김 허용, 동의 없는 광고 추적 없음. 이 조건을 맞추는 대표 수단이 Sign in with Apple이다. 예외(자체 계정만, 기업 · 교육 계정, 정부 · 업계 전자신분증 등)에는 이 스켈레톤이 해당하지 않는다.
- **애플 탈퇴 규칙**(Apple "Offering account deletion in your app" FAQ): "Apps that support Sign in with Apple should use the Sign in with Apple REST API to revoke user tokens." → 탈퇴할 때 토큰을 철회한다(§4.3).

## 1. 범위

**1차(끝에서 끝까지 도는 최소 버전)**: 앱이 구글 · 애플(iOS) · 카카오 · 네이버로 로그인하고, 같은 토큰으로 설정에서 연결하고 다시 인증하고 탈퇴한다. 애플 계정은 탈퇴할 때 토큰을 철회한다.

**비목표(이번에 안 함)**
- 안드로이드 · 웹에서 애플 로그인. 이건 Services ID, `form_post` 콜백 경로, 한국 개발자의 서버 간 알림 주소 등록이 같이 따라오는 일이다(정해야 할 것 1).
- 애플 서버 간 알림(이메일 전달 끔, 동의 철회, 애플 계정 삭제)을 받는 것.
- 서버가 발급하는 1회용 nonce. 1차는 업계 기본값인 "앱이 만든 nonce"를 쓴다(§4.1).
- 애플 refresh token을 저장해 두었다가 계정이 지워질 때(유예 끝) 자동 철회하는 것. 1차는 탈퇴 직전에 앱이 철회한다.
- 카카오 OIDC `idToken`의 경로(카카오는 액세스 토큰과 `access_token_info`로 충분하다).
- 앱의 "이메일 필수" 화면(`skeleton.account.email.required=true`). 기본값이 false다.
- react-skeleton 변경. 웹은 지금처럼 코드 흐름을 쓴다.

**다음에 넓힐 것(순서는 추천)**: ① 안드로이드 · 웹 애플(Services ID + 콜백) ② 애플 서버 간 알림 ③ refresh token 저장과 유예 끝 자동 철회(`AccountErasureListener`) ④ 서버 발급 nonce ⑤ 앱 "이메일 필수" 화면.

## 2. 서버 계약 — 토큰 교환 경로

### 2.1 로그인

`POST /api/v1/auth/social/{provider}/token` (공개, `auth`의 공개 경로 목록에 `POST /api/v1/auth/social/*/token` 한 줄 추가)

```json
{ "idToken": "…", "accessToken": "…", "nonce": "…", "displayName": "…" }
```

- 제공자마다 받는 토큰 종류가 하나다. google · apple은 `idToken`, kakao · naver는 `accessToken`. 맞는 필드가 없거나 둘 다 오면 `400 COMMON.VALIDATION_FAILED`.
- `nonce`는 원문이다(애플은 서버가 SHA-256 hex로 바꿔 비교한다). 모양 규칙은 지금 `NONCE_FORMAT`과 같다(8–256자).
- `displayName`은 애플만 쓴다. 애플은 이름을 **첫 로그인 때 SDK 응답에만** 주고 토큰에는 싣지 않는다. 그래서 앱이 넘긴다. 이 값은 **새 계정을 만들 때 닉네임 후보로만** 쓰고, 닉네임 규칙(N7)이 고친다. 다른 제공자는 무시한다.
- 응답: `200` + 지금 소셜 로그인과 같은 `AuthTokenResponse`(`Response.ok` 래퍼).
- 계정 연결 · 가입 · 병합 정책은 **지금 것을 그대로** 쓴다. 프로필은 `OAuthAccountProvisioningPolicy.resolveOrCreateAccount`로 넘기므로 `skeleton.account.social.sign-up`, `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`, 정지 · 삭제 유예 403이 코드 흐름과 똑같이 나온다. 같은 제공자 계정은 코드로 들어오든 토큰으로 들어오든 같은 `sub`이므로 같은 계정이다(구글 · 애플은 둘 다 `sub`, 카카오는 회원번호, 네이버는 `id`).

| 결과 | 코드 |
|---|---|
| 제공자가 꺼졌거나 토큰 입구가 없는 제공자(x, oidc) | `404 AUTH_SOCIAL.PROVIDER_NOT_FOUND` (지금 코드) |
| 필드 누락 · 엇갈림 | `400 COMMON.VALIDATION_FAILED` |
| nonce 필수인데 없음 · 모양 틀림 | `400 AUTH.SOCIAL_NONCE_FAILED` (지금 코드) |
| ID 토큰 서명 · iss · aud · exp · nonce 실패 | `401 AUTH.SOCIAL_ID_TOKEN_INVALID` (지금 코드) |
| 액세스 토큰 거절, 다른 앱의 토큰(카카오 `app_id` 불일치) | `401 AUTH.SOCIAL_ACCESS_TOKEN_INVALID` (**새 코드 하나**) |
| JWKS · 제공자 API 장애 | `502 AUTH_SOCIAL.PROVIDER_GATEWAY_ERROR` (지금 코드) |
| 연결 없음(가입 꺼짐) · 이메일 충돌 · 정지 등 | 지금 소셜 로그인과 같다 |

### 2.2 연결 · 다시 인증

`POST /account/identities/social/{provider}`와 `socialReauth`(이메일 변경 · 연결 · 연결 끊기 · 탈퇴 · discard · contact-email)는 `authorizationCode` 대신 `idToken` 또는 `accessToken`(+`nonce`)을 받는다. 셋 중 **정확히 하나**만 와야 한다(아니면 400). 나머지 규칙(다시 인증 순서, `400 ACCOUNT.REAUTH_FAILED`, 마지막 수단 보호, 제공자당 하나)은 그대로다.

### 2.3 `/auth/methods`

제공자 항목에 `"nativeToken": "id_token" | "access_token" | null` 한 칸을 더한다. 앱은 이 값이 null이 아닌 제공자만 단추로 보인다(서버에서 꺼진 제공자의 단추가 눌렸다가 404가 나는 일을 막는다).

### 2.4 코어에 더하는 것(`auth-social`)

- `OAuthProvider`에 `val nativeToken: NativeTokenKind? get() = null`과 `fun verifyNativeToken(token: OAuthNativeToken): OAuthUserProfile`(기본은 `OAuthProviderNotFoundException`)를 더한다. `OAuthNativeToken(token, nonce, displayName)`의 `toString`은 값을 가린다.
- `sealed interface OAuthCredential { Code(OAuthCodeExchange) ; Native(OAuthNativeToken) }`를 두고, 로그인 · 연결 · 다시 인증 세 곳이 `provider.resolveProfile(credential)` 하나를 부르게 한다. 지금 세 곳에 복사돼 있는 "제공자 호출 → 예외 갈래" 코드가 이 함수로 모인다.
- `IdTokenVerifier` · `JwksCache`를 `auth-social-oidc`에서 `auth-social`(`…auth.social.idtoken`)로 옮긴다. 구글 · 애플 모듈이 oidc 모듈을 끌어오지 않게 하려는 것이다. 바꾸는 점은 두 가지다. ① `iss`를 집합으로 받는다(구글은 `https://accounts.google.com`과 `accounts.google.com`을 둘 다 쓴다). ② nonce 비교를 함수로 받는다(애플은 SHA-256). oidc의 동작은 그대로다. CHANGELOG에 패키지 이동을 적는다.

## 3. 제공자별 검증

| 제공자 | 받는 것 | 검증 | 프로필 |
|---|---|---|---|
| google | idToken | JWKS `https://www.googleapis.com/oauth2/v3/certs`, RS256, `iss` ∈ 위 둘, `aud` = **웹 client id**(지금 `client-id`), exp/nbf, 요청에 nonce가 있으면 일치 | `sub`, `email`, `email_verified`, `name`, `picture`. `emailAuthoritative`는 지금 `GoogleEmailAuthority`를 따른다(`hd`는 이제 **서명 검증된** 토큰에서 읽는다) |
| apple | identityToken | JWKS `https://appleid.apple.com/auth/keys`, RS256, `iss` = `https://appleid.apple.com`, `aud` = 번들 id(`client-id`), exp, **nonce 필수**: 토큰의 `nonce` = SHA-256 hex(요청 원문) | `sub`, `email`, `email_verified`(문자열 "true" 또는 불리언), `is_private_email`, 이름은 요청의 `displayName` |
| kakao | accessToken | `GET kapi.kakao.com/v1/user/access_token_info` → **`app_id` = 설정 `app-id`** (다르면 401 ACCESS_TOKEN_INVALID). 401이면 같은 401 | 지금 `/v2/user/me` 매핑을 그대로 쓴다(코드 흐름과 같은 private 함수로 뺀다) |
| naver | accessToken | `GET openapi.naver.com/v1/nid/me`. 401이면 401. **어느 앱이 발급한 토큰인지 확인할 공식 API를 찾지 못했다**(정해야 할 것 2) | 지금 매핑 그대로 |

- **"aud를 여러 client id로 받기"는 두지 않는다.** 앱이 `serverClientId` = 웹 client id로 SDK를 초기화하면, 안드로이드와 iOS 토큰의 `aud`가 모두 웹 client id로 모인다(GIDConfiguration 설명: serverClientID가 ID 토큰의 audience로 돌아온다. 실기기 확인 필요). 같은 Google Cloud 프로젝트의 클라이언트만 이 aud를 받을 수 있다. 애플은 앱 하나에 번들 id가 하나다. 앱이 여럿이면 지금 있는 멀티 프로젝트 설정(`skeleton.projects`)으로 프로젝트마다 덮는다.
- 구글의 nonce: `google_sign_in` 7은 nonce를 `initialize`(앱 실행당 한 번)에만 받는다. 그래서 로그인마다 새 값을 줄 수 없다. 앱은 구글에 nonce를 보내지 않고, 서버는 "요청에 있으면 비교"만 한다.
- 애플 이메일의 권위: `emailVerified` = `email_verified`. `emailAuthoritative`는 **릴레이 주소(`privaterelay.appleid.com`)와 애플 도메인(`icloud.com` · `me.com` · `mac.com`)일 때만** true다. 애플 ID에 붙은 남의 도메인 주소는 구글의 비-Gmail과 같은 위험이 있다(주인이 바뀌어도 "확인됨"으로 남는다). 그래서 병합과 새 계정 주소에 쓰지 않는다.
- 카카오 설정 `skeleton.auth-social.providers.kakao.app-id`(콘솔의 "앱 ID" 숫자)가 새로 생긴다. **이 값이 있을 때만 카카오 토큰 입구가 열린다**(`nativeToken`이 null이 아니다). 구글은 제공자가 켜지면 열리고, 애플은 모듈의 `client-id`가 스위치다. 따로 켜는 플래그는 두지 않는다.

## 4. `auth-social-apple` 모듈

`auth-social-x`와 같은 틀이다. 제공자 하나짜리라 설정 접두사가 따로 있다(`skeleton.auth-social-apple`). `client-id`가 비어 있지 않으면 `autoEnabled`이고, 프로젝트별 덮기는 `OAuthProviderSource`로 한다. 기본은 꺼짐이다.

```yaml
skeleton:
  auth-social-apple:
    client-id: ""        # iOS 앱 번들 id (예: com.example.app). 비면 모듈이 꺼진다
    team-id: ""          # 애플 개발자 팀 id
    key-id: ""           # Sign in with Apple 키 id
    private-key: ""      # .p8 내용(PEM). 환경변수로만 넣는다
```

### 4.1 할 일
1. **네이티브 토큰 검증**(§3). `nonce` 필수(`NonceMode.REQUIRED`).
2. **인가 코드 교환**(`fetchProfile(exchange)`): `POST https://appleid.apple.com/auth/token`(`client_id` = 번들 id, `client_secret` = 아래 JWT) → `id_token` 검증 → 프로필. 네이티브 SDK도 `authorizationCode`를 같이 주므로 코드 흐름도 같은 모듈로 돈다. 철회(4.3)도 이 교환이 필요하다. **웹 리다이렉트는 지원하지 않는다**(관행 판단: 웹 애플은 Services ID가 따로 필요하다. 이메일 · 이름 scope를 쓰면 애플이 `form_post`로 **서버에** POST하므로 콜백 경로가 새로 생긴다. 한국 개발자는 Services ID를 등록할 때 서버 간 알림 주소도 내야 한다. 앱 심사 요건은 iOS만으로 채워진다 → 다음 단계).
3. **client_secret JWT**: ES256, 헤더 `kid` = key-id, `iss` = team-id, `sub` = client-id, `aud` = `https://appleid.apple.com`, `iat` = 지금, `exp` = 지금 + 5분. 부를 때마다 새로 만든다. 6개월 만료를 관리할 필요가 없고 캐시도 두지 않는다. 키는 기동할 때 파싱한다(틀리면 기동 실패, 메시지에 키 값을 넣지 않는다).

### 4.2 기존 흐름과 다른 점
- 이름: 요청의 `displayName`(§2.1).
- 이메일 숨김을 고른 사용자는 릴레이 주소를 갖는다. 우리 메일(가입 코드, 재설정, **탈퇴 확인 코드**)이 그 주소에 닿으려면 애플 개발자 콘솔 "Sign in with Apple for Email Communication"에 **보내는 도메인을 등록**해야 한다. 안 하면 애플 계정은 이메일로 다시 인증할 수 없어 탈퇴가 막힌다. `real-provider-setup.md` 애플 절에 필수로 적는다.

### 4.3 탈퇴 철회
`POST /api/v1/auth/social/apple/revoke` (로그인 필요) `{ "authorizationCode": "…" }` → 코드 교환 → `POST https://appleid.apple.com/auth/revoke`(`token` = refresh_token, `token_type_hint=refresh_token`) → `204`. 코드 거절은 `401 AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE`, 애플 장애는 502다. 순서는 앱이 정한다. 탈퇴 직전에 애플 시트를 한 번 더 띄워 코드를 받고 → revoke → `POST /account/delete`. 철회만 되고 탈퇴가 실패해도 해가 없다(다음 애플 로그인에서 다시 동의하고 같은 `sub`로 들어온다).

## 5. 보안

- **다른 앱의 토큰 거절**: 구글 · 애플은 `aud`로, 카카오는 `app_id`로 막는다. 네이버는 정해야 할 것 2.
- **재생**: ID 토큰은 수명이 짧다(애플 10분, 구글 1시간). 애플은 nonce 원문이 없으면 토큰만 훔쳐서는 쓸 수 없다(Firebase · Supabase와 같은 방식). 수명 안의 재생은 1차에서 감수한다. 서버 발급 nonce는 다음 단계다.
- **로그 금지**: 요청 DTO · `OAuthNativeToken` · `OAuthCredential`의 `toString`은 토큰을 가린다. `IdTokenRejected.reason`과 `OAuthTokenErrorLog`에는 토큰 · client_secret · 개인키가 들어가지 않는다. HTTP 로깅 태그는 본문을 찍지 않는다. 시험이 이를 고정한다(§6).
- JWKS: 지금 `JwksCache`를 쓴다(모르는 kid는 쿨다운 안에서 한 번만 다시 받는다). `alg`는 허용 목록에 있는 것만 받는다(RS256). `none`이나 HS 계열은 거절한다.

## 6. 시험(실제 제공자 호출 없음)

- `FakeIdp`(oidc 시험의 JDK `HttpServer`)를 `auth-social`의 `testFixtures`로 옮긴다. 시험이 RSA · EC 키를 만들어 JWKS를 내고, 토큰을 서명하고, 애플 `/auth/token` · `/auth/revoke`와 카카오 `access_token_info` · `/v2/user/me`, 네이버 `/v1/nid/me` 가짜를 세운다. 주소는 모듈의 `*-base-url` 덮기로 바꾼다.
- 꼭 고정할 것: 다른 aud · 다른 iss · 만료 · 서명 위조 · `alg=none`은 401. 애플 nonce 누락은 400, 불일치는 401. 카카오 `app_id` 다르면 401. 같은 `sub`는 코드로 들어오든 토큰으로 들어오든 같은 계정. 토큰이 로그와 예외 메시지에 안 나온다. client_secret JWT의 클레임과 서명(시험 EC 키로 검증). 다시 인증과 연결이 토큰으로 된다.
- 바뀐 모듈만 돌린다(`kscope.sh`). 전체 빌드는 하지 않는다.

## 7. 작업 목록(kotlin)

| 키 | 내용 |
|---|---|
| K1 | `IdTokenVerifier` · `JwksCache`를 `auth-social`로 옮기기(iss 집합, nonce 비교 함수) + `FakeIdp` testFixtures. oidc 시험 그대로 통과 |
| K2 | 코어: `NativeTokenKind` · `OAuthNativeToken` · `OAuthCredential` · `resolveProfile`, `/token` 경로 · 공개 경로 · OpenAPI · `/auth/methods`의 `nativeToken`, 새 에러 코드. 가짜 제공자로 시험 |
| K3 | `account`: 연결 · `socialReauth`가 토큰을 받게(DTO, `SocialLinkService`, `AccountSocialReauthVerifier`) + `account-http-contract.md` 추가 절 |
| K4 | google 토큰 검증(§3) |
| K5 | kakao `app-id` 설정(환경변수 별칭 포함) · `access_token_info` · 프로필 공유 |
| K6 | naver 토큰 검증(정해야 할 것 2의 답에 따라 넣거나 뺀다) |
| K7 | `auth-social-apple` 모듈: 설정, client_secret JWT, 토큰 · 코드 검증, revoke 경로, 프로젝트별 소스, 모듈 문서 · 설정 스니펫 · `capabilities.json` |
| K8 | `real-provider-setup.md` 애플 절(App ID capability, .p8 키, 릴레이 메일 도메인 등록, 9절 "이번에는 제외" 문구 교체), 카카오 앱 ID 위치, CHANGELOG |

순서: K1 → K2 → (K3 · K4 · K5 · K6 · K7 병행 가능) → K8.

---

# flutter 절

## 8. 패키지

레포 규칙(폴더 `snake_case`, 패키지 `skeleton_<폴더>`, 카탈로그 id `kebab`)과 kotlin의 제공자별 모듈 구성에 맞춰 **코어 하나 + 제공자별 네 개**로 나눈다. 프로젝트는 쓰는 SDK만 들인다. 네이버 SDK는 안드로이드 `MainActivity`를 `FlutterFragmentActivity`로 바꾸게 하고, 카카오 SDK는 전용 Activity를 요구한다. 한 패키지로 묶으면 안 쓰는 제공자의 플랫폼 설정까지 강제된다.

| 폴더 / 패키지 / id | 의존 | 하는 일 |
|---|---|---|
| `social_login` / `skeleton_social_login` / `social-login` | `api_client`, `auth` | `SocialCredential`(`toString` 가림), `SocialProvider` 인터페이스(`id`, `obtain(nonce)`, `signOut()`), nonce 생성(`Random.secure` 32바이트 base64url), `SocialAuthApi`(`/token` 로그인 · 연결 · `socialReauth` JSON 만들기), `socialLoginController`(받기 → 서버 → `AuthSession.signInWith`), `/auth/methods`의 `nativeToken`으로 보일 제공자 거르기. 취소는 오류가 아니다(`SocialCancelled`) |
| `social_login_google` | `google_sign_in` ^7.2 | `initialize(serverClientId: 웹 client id)` → `authenticate()` → `idToken`. nonce는 보내지 않는다(§3) |
| `social_login_apple` | `sign_in_with_apple` ^8 | iOS만 보인다(`Platform.isIOS`). SDK에 `sha256(nonce)`를 주고 서버에는 원문을 보낸다. `givenName` · `familyName` → `displayName`. `revokeBeforeDelete()`(§4.3) |
| `social_login_kakao` | `kakao_flutter_sdk_user` ^2.0 | 카카오톡 앱이 있으면 톡으로, 없으면 계정 로그인 → `accessToken` |
| `social_login_naver` | `flutter_naver_login` ^2.1 | `logIn()` → `getCurrentAccessToken()`. 가장 많이 쓰는 패키지지만 2.1.1이 약 17개월 전에 나왔고 업로더가 미인증이다. 대안 `naver_login_sdk`(인증 퍼블리셔, 사용자 적음)도 README에 적는다 |

`auth` 패키지도 같이 고친다. 소셜 전용 계정(비밀번호 없음)이 앱 안에서 탈퇴할 수 있어야 하기 때문이다. `deleteAccount({currentPassword?, confirmationCode?, socialReauth?})`, `requestDeleteConfirmation()`(`POST /account/delete/confirmation`), `/account/me`의 `hasPassword` · `email`을 읽어 확인 방식을 고른다. 주소가 없으면 앱이 넘긴 `Future<Map<String, Object?>?> Function()`(소셜 다시 인증)을 부른다. `auth`는 `social_login`을 import하지 않는다.

## 9. 앱 연결

- **sample**: 로그인 화면에 네 단추(애플은 iOS만, `SignInWithAppleButton` 사용 — HIG), 설정에 "소셜 연결"(다시 인증 포함), 탈퇴(애플 계정이면 `revokeBeforeDelete` → 삭제). `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`는 "그 이메일로 가입된 계정이 있습니다. 그 계정으로 로그인한 뒤 설정에서 연결하세요"로, 연결 없음 409는 "가입되지 않은 계정"으로 보인다.
- **starter**: 건드리지 않는다(push · iap와 같은 관행). 찍은 프로젝트는 README의 연결 예시(위 sample 코드의 축약)를 따른다.
- 단추 모양은 앱 몫이다. 카카오 · 네이버 로고 이미지는 레포에 넣지 않는다(각 사의 디자인 가이드 링크만 둔다).

## 10. 플랫폼 설정(키는 env, 레포에는 가짜와 예시만)

- Dart가 읽는 값: `env/<env>.json`에 `GOOGLE_SERVER_CLIENT_ID`, `GOOGLE_IOS_CLIENT_ID`, `KAKAO_NATIVE_APP_KEY`(`String.fromEnvironment`). 빈 값이면 그 제공자 단추를 숨긴다.
- 네이티브 파일이 읽는 값(Info.plist URL scheme, 매니페스트 meta-data)은 dart-define에서 끌어오지 않는다. 대신 **git 밖의 설정 파일 두 개**에 둔다. iOS `ios/Flutter/Social.xcconfig`(`Debug` · `Release.xcconfig`가 `#include? "Social.xcconfig"` — 없으면 조용히 빈 값)와 안드로이드 `android/social.properties`(`app/build.gradle.kts`가 읽어 `manifestPlaceholders`로 넘긴다)다. 둘 다 `.example`을 커밋한다. dart-define을 xcconfig로 풀어 쓰는 스크립트는 첫 빌드에 값이 안 들어가는 함정이 있어 쓰지 않는다. 카카오 키는 json과 설정 파일 두 곳에 같은 값이 들어간다(README에 적는다).
- iOS `Info.plist`(Debug용 `Info-Debug.plist`도 같이): `CFBundleURLTypes`에 `$(GOOGLE_REVERSED_CLIENT_ID)` · `kakao$(KAKAO_NATIVE_APP_KEY)` · `$(NAVER_URL_SCHEME)`, `GIDClientID` · `GIDServerClientID`, `LSApplicationQueriesSchemes`에 `kakaokompassauth` · `kakaolink` · `naversearchapp` · `naversearchthirdlogin`, `NidClientID` · `NidClientSecret` · `NidAppName` · `NidUrlScheme`. `Runner.entitlements`에 `com.apple.developer.applesignin = [Default]`.
- Android: 카카오 `AuthCodeHandlerActivity`(scheme `kakao${KAKAO_NATIVE_APP_KEY}`), 네이버 meta-data `com.naver.sdk.clientId` · `clientSecret` · `clientName`, `MainActivity` → `FlutterFragmentActivity`. 구글은 콘솔에 패키지명과 SHA-1을 등록한다(파일 변경 없음).
- 네이버 SDK는 설계상 client secret을 앱에 넣는다(서버 코드 흐름과 같은 값). 네이버 공식 SDK 방식이라 피할 수 없다. README에 사실로 적는다.

## 11. new-project · 카탈로그

- `stampFlag`: 다섯 개 모두 `flag`(`--packages social-login-kakao,social-login-apple` 등). 제공자 패키지가 `social_login`을 pubspec으로 끌어온다(지금 닫힘 규칙). 스타터 플랫폼 파일은 바꾸지 않으므로 `stamp.dart`의 바꾸는 목록은 그대로다.
- `capabilities.json`: 다섯 항목. `backend.modules`는 코어 `auth-social`, google → `auth-social-google`, apple → `auth-social-apple`, kakao → `auth-social-kakao`, naver → `auth-social-naver`. 코어의 `pairsWith.react`는 `["social-login"]`. `auth` 항목의 `notFor`에서 "Social login: not built yet"을 지운다.

## 12. 시험

- 코어: 가짜 `SocialProvider` + 지금 `test/support/backend_fake.dart` 방식. 고정할 것: 토큰이 `toString` · 로그 · 예외에 안 나온다. 취소는 오류 상태를 만들지 않는다. 409 갈래, 성공하면 세션이 `SignedIn`이 된다.
- 제공자 패키지: 플랫폼 채널 · `GoogleSignInPlatform`을 가짜로 바꾼다. 애플은 SDK에 준 nonce가 원문의 sha256이고 서버에는 원문이 가는지 본다.
- `auth`: 비밀번호 · 확인 코드 · 소셜 다시 인증 세 갈래의 탈퇴.
- 실제 SDK · 콘솔 · 실기기는 시험하지 못한다(§13).

## 13. 작업 목록(flutter)

| 키 | 내용 |
|---|---|
| F1 | `social_login` 코어 + 시험 + README |
| F2 | `auth`: 비밀번호 없는 계정의 탈퇴(확인 코드 · 소셜 다시 인증 훅) · `hasPassword` |
| F3 | `social_login_google` · `social_login_apple`(철회 포함) |
| F4 | `social_login_kakao` · `social_login_naver`(정해야 할 것 2에 따라) |
| F5 | sample 화면 · 플랫폼 설정 파일과 `.example` · `capabilities.json` · `stampFlag` · CLAUDE.md 범위 문장 |

순서: (K2 · K3 끝난 뒤) F1 → F2 → (F3 · F4) → F5.

## 14. 확인 못 한 것

- `serverClientId`를 줄 때 iOS 토큰의 `aud`가 웹 client id인지(GIDConfiguration 설명만 확인했고 실기기는 못 봤다).
- 네이버 `id`가 앱마다 다른 값인지, 네이버에 토큰 발급 앱을 확인하는 API가 있는지(공식 문서 사이트를 가져오지 못했다).
- `kakao_flutter_sdk` 2.0.0의 API 변경(1.x 기준으로 썼다). `sign_in_with_apple` 8.x의 필드 이름.
- 한국 개발자 서버 간 알림 주소 요건이 Services ID 없이 App ID만 쓸 때도 해당하는지(FAQ는 "registering a new Services ID"라고만 한다).

## 정해야 할 것

1. **아이폰에서 애플로 가입한 사람이 안드로이드 폰으로 바꾸면 어떻게 할까?** 1차는 애플 단추가 iOS에만 있다. 예: 아이폰에서 "애플로 로그인"(이메일 숨김)으로 가입한 사람이 갤럭시로 옮기면 애플 단추가 없다.
   - (추천) **1차는 iOS만 둔다.** 그 사람은 릴레이 주소로 비밀번호 재설정 메일을 받아 들어온다(4.2의 메일 도메인 등록이 전제). 심사 요건은 iOS만으로 채워진다. 안드로이드 · 웹 애플은 다음 단계에 Services ID와 콜백을 붙인다.
   - 1차에 안드로이드 · 웹 애플까지 넣는다. 콜백 경로, Services ID, 한국 서버 간 알림 주소가 같이 들어와 작업이 1.5배쯤 된다.
2. **네이버 앱 로그인을 어떻게 할까?** 네이버는 "이 토큰이 우리 앱에서 발급됐는지" 확인하는 길을 못 찾았다. 네이버 회원 id가 앱마다 같은 값이면 사고가 난다. 예: 누군가 네이버 로그인을 붙인 다른 앱(퀴즈 앱 등)을 만들고, 거기 로그인한 사람의 토큰으로 우리 서버에 그 사람으로 들어올 수 있다.
   - (추천) **먼저 실측한다.** 네이버 개발자센터에 앱 두 개를 만들고 같은 네이버 아이디로 로그인해 `id`를 비교한다(10분). 다르면(앱별) 위험이 없으니 그대로 넣는다. 같으면 1차에서 네이버 앱 로그인을 빼고, 앱의 네이버 단추는 다음 단계에서 웹 로그인 화면(코드 흐름)으로 연다.
   - 위험을 알고 그대로 넣는다(국내 서비스가 흔히 쓰는 방식).
   - 1차에서 네이버를 아예 뺀다.

## 결정됨 (2026-11-04 형: "국룰로 가 국룰로. 다른데 따라가")

1. 애플: 관행대로 1차는 iOS 앱에만 애플 단추. 안드로이드 · 웹 애플은 다음 단계.
2. 네이버: 관행대로 앱 SDK accessToken → 서버가 프로필 API 로 확인해 그대로 넣는다(국내 서비스 흔한 방식). 모듈은 기본 꺼짐이고, 설정 문서에 「앱별 id 여부는 확인 안 됨」 한 줄만 적는다. 추가 장치는 두지 않는다.
