# auth-social-x

**X(Twitter) 로그인** — OAuth 2.0 Authorization Code + **PKCE(필수)**, 기밀(confidential) 클라이언트. 토큰 엔드포인트는 HTTP Basic(`client_id:client_secret`), 프로필은 `GET /2/users/me`.
`skeleton.auth-social-x.client-id` 가 있을 때만 켜진다 (별도 스위치 없음). 로그인 경로 `/api/v1/auth/social/x/login`, 로그인 수단 이름 `x`.

| 하는 일 | 값 · 근거 |
|---|---|
| 인가 URL | `https://x.com/i/oauth2/authorize` — `response_type=code`, `client_id`, `redirect_uri`(콘솔의 Callback URI 와 **정확히 일치**), `scope`, `state`, `code_challenge`, `code_challenge_method=S256` ([문서](https://docs.x.com/resources/fundamentals/authentication/oauth-2-0/authorization-code)) |
| 스코프 | `users.read tweet.read` (`offline.access` 불필요 — 리프레시 토큰을 쓰지 않는다). `request-email: true` 면 `users.email` 추가 |
| 토큰 | `POST https://api.x.com/2/oauth2/token` — Basic 인증, 본문 `grant_type=authorization_code`, `code`, `redirect_uri`, `code_verifier`. 기밀 클라이언트는 `client_id` 를 본문에 안 보내도 된다(문서). **인가 코드는 30초 안에 교환해야 한다**(문서) — 프론트는 콜백을 받자마자 백엔드를 불러야 한다 |
| 프로필 | `GET https://api.x.com/2/users/me?user.fields=profile_image_url[,confirmed_email]` (Bearer) → `data.id`(= 계정의 제공자 주체) · `username` · `name` · `profile_image_url` ([문서](https://docs.x.com/x-api/users/get-my-user)) |
| 이메일 | `request-email: true` + 콘솔의 **이메일 요청 권한**(앱 설정 "Request email from users" — 개인정보처리방침 · 약관 URL 필요, [공지](https://devcommunity.x.com/t/announcing-support-for-email-address-retrieval-with-oauth-2-0-in-the-x-api-v2/240555)) 이 있으면 `confirmed_email` 이 온다. 없으면 필드가 오지 않고 계정은 **주소 없는 계정**이다. **확인됨으로 치지 않는다**(`trust-confirmed-email: false` 기본 — 필드 이름은 "confirmed" 지만 병합에 쓸 만큼 보증한다는 문서 문장은 확인하지 못했다: **확인 필요**) |
| 오류 | 코드 거부 → 401, 검증기 불일치(`invalid_request` + verifier 문구) → 400 `AUTH.SOCIAL_PKCE_FAILED`, **client 인증 실패(401)는 설정 오류라 502**, `/2/users/me` 429 → `XRateLimitedException`(`x-rate-limit-reset` 보존) → 502, 403 → 502 + X 의 `title` · `reason` · `detail` 을 로그에 |

**API 접근 조건 (확인 필요)**: X API 는 유료/티어 정책이 자주 바뀐다. 이 흐름이 쓰는 호출은 OAuth 2.0 사용자 컨텍스트의 `/2/users/me` 하나다. 무료 티어에서 이 엔드포인트와 `users.email` 이 열려 있는지, 월 사용량 · 레이트 리밋(15분당 요청 수)이 얼마인지는 이 문서를 쓴 시점에 공식 문서로 확인하지 못했다 — **내일 실제 앱으로 `/2/users/me` 가 200 인지 확인한다**. 403 `client-forbidden`/`client-not-enrolled`(`reason`)이 오면 앱이 Project 에 속하지 않았거나 티어가 모자란 것이다(오류 본문이 서버 로그에 그대로 남는다).

## 콘솔 체크리스트 (X)
1. [developer.x.com](https://developer.x.com/en/portal/dashboard) → **Project** 와 그 안의 **App** 을 만든다.
2. App 의 **User authentication settings** → **OAuth 2.0** 켬 → **Type of App: Web App, Automated App or Bot** (= 기밀 클라이언트; "Native App / Single page App" 은 공개 클라이언트라 Client Secret 이 없다 — 이 모듈은 쓰지 않는다).
3. **Callback URI / Redirect URL** 에 정확히 아래를 등록한다 (정확 일치 검증 — 문서):
   - 로컬: `http://127.0.0.1:5173/auth/callback` (X 가 `localhost` 대신 `127.0.0.1` 을 요구하는지는 **확인 필요**; 프론트 개발 서버를 `127.0.0.1` 로 열어 쓴다)
   - 배포: `https://<도메인>/auth/callback`
   - **Website URL** 도 필수다.
4. **Keys and tokens** 에서 **OAuth 2.0 Client ID** (= `client-id`) 와 **Client Secret** (= `client-secret`, 운영자 비밀) — **OAuth 1.0a 의 API Key/Secret 이 아니다.**
5. (선택, 이메일) 앱 설정에서 이메일 요청 권한을 켜고 개인정보처리방침 · 약관 URL 을 넣은 뒤 `request-email: true`.
6. 앱 설정: `<P>_AUTH_SOCIAL_X_CLIENT_ID`(env:) · `<P>_AUTH_SOCIAL_X_CLIENT_SECRET`(운영자) · `<P>_AUTH_SOCIAL_X_REDIRECT_URI`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social-x"))` |
| 함께 오는 모듈 | `platform`, `auth-social`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-social-x` — [docs/config/modules/auth-social-x.yml](../config/modules/auth-social-x.yml) |
| 기본 동작 | 꺼짐. `client-id` 를 적으면 켜진다 (별도 `enabled` 없음). |
| 부팅에 필요한 것 | 없음. 켠 뒤에는 `client-secret` 이 있어야 한다 (없으면 기동 실패: `skeleton.auth-social-x.client-secret must not be blank`). |
| 교체 지점 | `XOAuthProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-social-x/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
