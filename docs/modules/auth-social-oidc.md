# auth-social-oidc

**범용 OpenID Connect 제공자.** 속성만으로 제공자를 더한다 — 맵의 키(`line`, `microsoft` …)가 제공자 코드가 되어 로그인 경로 `/api/v1/auth/social/<코드>/login` 과 로그인 수단 이름이 된다.
한 모듈 인스턴스가 제공자 여럿을 낸다. `client-id` 를 적은 제공자만 만들어진다 (비어 있으면 빈도 네트워크 호출도 없다).
인가 코드 → 토큰 교환(PKCE `code_verifier` 전달) → **ID 토큰 검증**(서명 · `iss` · `aud`/`azp` · `exp` · `nbf` · `nonce`) → 필요하면 userinfo → 프로필. 코드는 `account` 를 모른다.

| 하는 일 | 방식 |
|---|---|
| 엔드포인트 | `issuer` 의 `/.well-known/openid-configuration`(discovery, 캐시 24h, 실패해도 옛 문서 유지) 또는 명시 엔드포인트 셋(`authorization-endpoint` · `token-endpoint` · `jwks-uri`). 켜진 제공자의 discovery 가 시작할 때 안 되면 **기동 실패**(메시지에 제공자 코드 · issuer · 고치는 법) |
| 서명 검증 | 허용 알고리즘 목록(`id-token-algorithms`)만. HS256 은 client secret, RS256 · ES256 은 JWKS 의 같은 종류 키. JWKS 는 1h 캐시, 모르는 `kid` 는 키 교체로 보고 한 번만 다시 받는다(30s 쿨다운). `alg: none` · 목록 밖 알고리즘 · 다른 `kid` 의 같은 이름 키는 거부 |
| 이메일 | `email-trust`: `CLAIM`(`email_verified` 가 true 일 때만 확인됨, 문자열 `"true"` 도 인정) · `NEVER`(항상 미확인) · `ALWAYS`. 미확인 이메일은 계정이 **없는 것으로** 친다 ([accounts.md](../accounts.md)) |
| 프로필 | 클레임 이름을 바꿀 수 있다(`claims.*`). 사진은 https 만 |
| 오류 | 코드 거부(`invalid_grant`) → 401, 검증기 불일치 → 400 `AUTH.SOCIAL_PKCE_FAILED`, ID 토큰 거부 → 401 `AUTH.SOCIAL_ID_TOKEN_INVALID`(사유는 서버 로그에만), **우리 client 설정 거부(`invalid_client`)는 사용자 잘못이 아니라 502** |

## 제공자를 설정만으로 더하는 법 (예: Microsoft Entra ID)

```yaml
skeleton:
  auth-social-oidc:
    providers:
      microsoft:
        issuer: https://login.microsoftonline.com/<테넌트 id>/v2.0
        client-id: ${MICROSOFT_CLIENT_ID:}
        client-secret: ${MICROSOFT_CLIENT_SECRET:}
        redirect-uri: https://<도메인>/auth/callback
        scopes: [openid, profile, email]
        email-trust: NEVER          # Entra 의 email 클레임은 소유가 증명된 값이 아니다 (확인 필요 — 제공자 문서로 정한다)
```
끝이다 — 추가할 코드는 없다. 프론트는 `GET /api/v1/auth/methods` 의 `microsoft` 항목(`authorize.url` · `scopes` · `pkce` · `nonce`)으로 버튼을 만든다.
다른 제공자도 같은 형태다: `issuer`(또는 명시 엔드포인트) + `client-id` + `client-secret`, 필요하면 `pkce` · `nonce` · `token-endpoint-auth` · `id-token-algorithms` · `claims` 를 제공자 문서에 맞춘다.

## LINE 프리셋 (`providers.line`)

`client-id`(Channel ID) 와 `client-secret`(Channel secret) 만 적으면 된다. 프리셋 값(전부 속성으로 덮어쓸 수 있다):

| 칸 | 프리셋 값 | 근거 |
|---|---|---|
| 엔드포인트 | authorize `https://access.line.me/oauth2/v2.1/authorize` · token `https://api.line.me/oauth2/v2.1/token` · JWKS `https://api.line.me/oauth2/v2.1/certs` · userinfo `https://api.line.me/oauth2/v2.1/userinfo`, issuer `https://access.line.me` | [LINE Login v2.1 reference](https://developers.line.biz/en/reference/line-login/) · [discovery 문서](https://access.line.me/.well-known/openid-configuration) |
| 스코프 | `openid profile` (이메일은 아래) | [integrate-line-login](https://developers.line.biz/en/docs/line-login/integrate-line-login/) |
| PKCE · nonce | `REQUIRED` · `REQUIRED` (S256 만 지원) | 같은 문서 · discovery `code_challenge_methods_supported: ["S256"]` |
| 클라이언트 인증 | `POST` (body 의 `client_id` · `client_secret`) | 같은 문서 (discovery 는 `client_secret_basic` 도 나열한다) |
| ID 토큰 서명 | `HS256`(웹 로그인, **channel secret 으로 검증**) · `ES256`(네이티브 · LIFF, JWKS) | [ID token details](https://developers.line.biz/en/docs/line-login/verify-id-token/): "for web login, HS256 … is returned" |
| userinfo | 부르지 않는다 (LINE userinfo 에는 이메일이 없다 — `sub` · `name` · `picture` 뿐) | LINE Login v2.1 reference |
| 이메일 확인 | **항상 미확인(`email-trust: NEVER`)** | 아래 |

- **왜 verify 엔드포인트(`POST /oauth2/v2.1/verify`)가 아니라 로컬 검증인가**: 로그인마다 LINE 호출이 한 번 더 늘지 않고, 그 엔드포인트의 장애가 로그인을 막지 않으며, 같은 코드 경로가 ES256(JWKS) 와 다른 OIDC 제공자에도 쓰인다. HS256 은 channel secret 으로 직접 검증한다(문서 그대로). 두 방식의 결과는 같다(`iss` · `aud` · `exp` · `nonce` 를 같은 규칙으로 본다).
  discovery 문서는 `id_token_signing_alg_values_supported: ["ES256"]` 만 적지만 웹 로그인 문서는 HS256 을 말한다 — **둘 다 받는다**. 실제 토큰의 `alg` 는 오늘 실제 채널로 확인 필요.
- **이메일**: `email` 스코프 **그리고** 콘솔의 이메일 권한 승인(Basic settings > OpenID Connect > Email address permission > Apply, 이메일 수집 안내 화면 스크린샷 제출)이 있어야 ID 토큰에 `email` 이 실린다. 승인 전에 `email` 스코프를 요청하면 LINE 이 인가를 거부하는지는 **확인 필요**라서 프리셋 기본 스코프에는 넣지 않았다. 승인 뒤에 `scopes: [openid, profile, email]`.
  이메일이 없으면 계정은 **주소 없는 계정**이다(Naver 와 같다).
- **LINE 이메일은 "확인됨" 이 아니다**: LINE 문서는 ID 토큰에 `email_verified` 같은 클레임을 정의하지 않고 이메일의 소유 확인 여부도 말하지 않는다([verify-id-token](https://developers.line.biz/en/docs/line-login/verify-id-token/)) → **계정 병합 · 연결에서 이메일을 믿지 않는다** (`emailVerified=false`, 같은 이메일의 기존 계정에 병합하지 않고 충돌도 알리지 않는다). 문서가 보증을 명시하면 `email-trust: CLAIM|ALWAYS` 로 바꿀 수 있다.
- **`sub` 의 안정성**: LINE 의 user ID 는 **LINE Provider(콘솔의 묶음) 단위**로 같다 — 같은 Provider 아래 채널(LINE Login · Messaging API)끼리는 같고, **다른 Provider 의 채널에서는 같은 사람의 값이 다르다**([문서](https://developers.line.biz/en/docs/messaging-api/getting-user-ids/)). 결과: 채널을 같은 Provider 안에서 바꾸는 것은 안전하지만, **다른 Provider 로 옮기거나 새 Provider 로 채널을 다시 만들면 모든 사용자가 새 로그인 수단(새 계정)이 된다.** 처음부터 채널을 만들 Provider 를 정하고 바꾸지 않는다. (작업 지시의 "채널 단위" 는 문서상 "Provider 단위" 가 정확하다.)
- 로그인 코드 수명 10분 · 한 번 쓰기 (LINE 문서).

## 환경변수로 설정할 때 (Map 값이라 별칭이 필요하다)
`providers.<코드>.*` 는 Map 값이라 스프링의 느슨한 바인딩만으로는 `SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_ID` 가 묶이지 않는다(`_` 가 모두 칸 구분이 된다 — `OidcEnvironmentVariablesTest` 가 진짜 `systemEnvironment` 모양으로 증명). 그래서 `OidcEnvironmentAliasPostProcessor`(`spring.factories`)가
`SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_<코드>_<키>` 를 `skeleton.auth-social-oidc.providers.<코드 소문자>.<키: 소문자, _ 는 ->` 로 다시 적는다 (`auth-social` 의 `AuthSocialEnvironmentAliasPostProcessor` 와 같은 규칙).
**코드는 한 단어(영문 · 숫자)**여야 한다 — `line-jp` 같은 여러 단어 코드는 환경변수로 쓸 수 없으니 yml 로. 중첩은 `…_CLAIMS_EMAIL_VERIFIED` → `claims.email-verified` 만 지원하고 `authorize-params` 같은 맵 키는 yml 로. 값이 빈 변수는 건너뛴다.

## 콘솔 체크리스트 (LINE)
1. [LINE Developers Console](https://developers.line.biz/console/) → Provider 를 만든다(위 `sub` 주의) → **LINE Login** 채널 생성(App type: **Web app**).
2. Basic settings 의 **Channel ID**(= `client-id`), **Channel secret**(= `client-secret`, 운영자 비밀 — `docs/deploy.md` §7).
3. **LINE Login** 탭 → **Callback URL** 에 정확히 아래를 등록한다 (글자 하나까지 같아야 한다 — 쿼리 · 슬래시 포함). 로그인 요청의 `redirectUri` 가 인가 요청과 같아야 한다:
   - 로컬: `http://localhost:5173/auth/callback` (LINE 이 http localhost 를 허용하는지는 **확인 필요** — 안 되면 터널 주소의 https 로)
   - 배포: `https://<도메인>/auth/callback`
4. (선택) Basic settings > OpenID Connect > **Email address permission** 신청 → 승인 뒤 `scopes` 에 `email`.
5. 채널 상태: 개발 중(Developing) 에는 채널 관리자 · 테스터만 로그인된다 — 공개하려면 **Published** 로 바꾼다.
6. 앱 설정 — 환경변수 이름(실측: 진짜 환경변수로 apps/sample 을 띄워 `/auth/methods` 에 `line` 이 `clientId` 와 함께 나옴): `SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_ID`(env:) · `…_LINE_CLIENT_SECRET`(운영자) · `…_LINE_REDIRECT_URI`(env:) · `…_LINE_SCOPES=openid,profile,email`(콘솔 이메일 승인 뒤). (`<P>_` 는 배포 플랫폼의 접두 규칙, 스켈레톤 기본 `SKELETON_`.) 프론트가 `GET /auth/methods` 의 `line` 항목으로 인가 URL 을 만든다 ([계약](../account-http-contract.md) "FINAL-3 + social PKCE").

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social-oidc"))` |
| 함께 오는 모듈 | `platform`, `auth-social`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-social-oidc` — [docs/config/modules/auth-social-oidc.yml](../config/modules/auth-social-oidc.yml) |
| 기본 동작 | 꺼짐. `providers.<코드>.client-id` 를 적은 제공자만 켜진다 (별도 `enabled` 없음). |
| 부팅에 필요한 것 | 없음. 켠 제공자는 `client-secret`(`token-endpoint-auth=none` 이면 생략) · `issuer` 또는 명시 엔드포인트 셋이 있어야 하고 https 여야 한다(localhost 만 http) — 아니면 기동 실패. |
| 교체 지점 | `OidcProviderFactory`, `OidcOAuthProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-social-oidc/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
