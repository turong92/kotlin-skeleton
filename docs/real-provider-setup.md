# 진짜 제공자 시험 가이드 — 소셜 로그인(Google · LINE · X) + 진짜 메일

처음 해 보는 사람이 위에서부터 따라 하면 **내 도메인으로 진짜 소셜 로그인과 진짜 메일 발송을 시험**할 수 있게 쓴 문서다.
`확인 필요` 로 표시한 것은 요금 · 한도 · 콘솔 화면처럼 시간이 지나면 바뀌는 사실이라 **이 문서를 쓸 때(2026-10) 공식 문서에서 읽은 값**이지만, 가입하기 전에 한 번 더 확인한다.
값은 코드에서 확인했다(redirect URI 는 프런트 `react-skeleton` 의 라우트와 이 백엔드의 `GET /api/v1/auth/methods`).

| 순서 | 할 일 | 걸리는 시간(처음) |
|---|---|---|
| §1 | Google OAuth 클라이언트 만들기 | 20분 |
| §2 | LINE Login 채널 만들기 (이메일 권한 신청은 심사가 걸릴 수 있다) | 20분 + 심사 |
| §3 | X(Twitter) 개발자 앱 만들기 | 20분 |
| §4 | (선택) Kakao · Naver | 15분 |
| §5 | 메일 발송 서비스 고르고 DNS 에 레코드 넣기 (Cloudflare) | 30분 + DNS 반영 |
| §6 | 환경변수 채우기 (로컬 · 홈서버) | 10분 |
| §7 | 점검 스크립트 + 눌러 볼 순서 | 40분 |

> **환경변수 이름은 한 규칙**이다(`docs/deploy.md` §7): Google · Kakao · Naver 는 `SKELETON_AUTH_SOCIAL_PROVIDERS_<제공자>_{ENABLED,CLIENT_ID,CLIENT_SECRET,REDIRECT_URI}`, LINE 은 `SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_{CLIENT_ID,CLIENT_SECRET,REDIRECT_URI,SCOPES}`, X 는 `SKELETON_AUTH_SOCIAL_X_{CLIENT_ID,CLIENT_SECRET,REDIRECT_URI,REQUEST_EMAIL}`. 진짜 환경변수만으로 `apps/sample` 을 띄워 셋이 `/auth/methods` 에 나오는 것은 `SocialEnvironmentOnlyBootTest` 가 증명한다. 콘솔에서 만드는 준비(§1 · §2 · §3)는 코드와 무관하니 먼저 해 둔다.

## 0. 이 프로젝트가 로그인에 쓰는 주소 (모든 제공자에 공통)

소셜 로그인은 "프런트 → 제공자 화면 → 프런트(콜백) → 백엔드가 코드를 토큰으로 교환" 순서다. 제공자 콘솔에 등록하는 **redirect URI 는 프런트의 콜백 주소**이고, **글자 그대로**(스킴 · 호스트 · 포트 · 경로 · 끝의 `/` 까지) 같아야 한다.

| 용도 | 경로 (프런트 라우트) | 로컬 | 내 도메인 |
|---|---|---|---|
| 로그인 · 가입 | `/auth/callback` | `http://localhost:5173/auth/callback` | `https://<도메인>/auth/callback` |
| 계정 설정에서 연결 · 해제 · 다시 인증 | `/account/link-callback` | `http://localhost:5173/account/link-callback` | `https://<도메인>/account/link-callback` |

- 로컬 프런트는 Vite(`scripts/dev-sample.sh` 가 5173 으로 띄운다). 포트를 바꿨으면 그 포트로 적는다.
- 연결용 콜백이 따로 있는 이유: 로그인 콜백과 같은 주소를 쓰면 "연결하려던 왕복"과 "로그인하려던 왕복"이 섞인다. **두 주소를 다 등록한다** (코드: 프런트 `createSocialLinkFlow` 는 항상 `origin + /account/link-callback` 을 쓴다).
- **로그인용 주소는 어디서 정해지나**: 백엔드의 `…_REDIRECT_URI` 가 있으면 그 값이 `GET /api/v1/auth/methods` 의 `redirectUri` 로 나가 프런트가 그대로 쓴다. 없으면 프런트가 `자기 origin + /auth/callback` 을 쓴다. 이 시험에서는 **값을 넣는다**(§6) — `/auth/methods` 로 눈에 보이고, 점검 스크립트가 비교해 준다.
- 홈서버에서 프런트와 API 가 같은 도메인이면(`web: dist` + `api_prefix: /api/v1`) `https://<도메인>` 하나다. `http://` 는 로컬 시험 말고는 쓰지 않는다.
- 메일 속 링크(비밀번호 재설정 · 매직 링크)가 여는 프런트 주소는 `SKELETON_ACCOUNT_MAIL_LINK_BASE_URL` — 로컬 `http://localhost:5173`, 홈서버 `https://<도메인>`.

## 1. Google OAuth 클라이언트

> 사용하는 값: 백엔드는 `https://oauth2.googleapis.com/token` 으로 코드를 교환하고 `https://openidconnect.googleapis.com/v1/userinfo` 에서 `sub` · `email` · `email_verified` · `name` 을 읽는다. 프런트가 요청하는 scope 는 `openid email profile`. 이 셋은 "민감하지 않은" scope 라 **앱 검증(verification) 없이** 쓴다.

1. <https://console.cloud.google.com/> 에 로그인 → 위쪽 프로젝트 선택 → **새 프로젝트** (이름 예: `notes-login`).
2. 왼쪽 메뉴 **API 및 서비스 → OAuth 동의 화면**(요즘 이름: **Google Auth Platform**) → **시작하기**.
   - 앱 이름: 사용자에게 보이는 서비스 이름. 사용자 지원 이메일: 내 주소. 개발자 연락처 이메일: 내 주소.
   - **대상(Audience): 외부(External)** — 아무 구글 계정이나 로그인하게 하려면 외부. (내부는 Google Workspace 조직 안에서만.)
3. **대상(Audience) 메뉴 → 게시 상태: 테스트(Testing)** 로 둔다. 이 상태에서는
   - **테스트 사용자**로 등록한 계정만 로그인된다 (최대 100명). **"테스트 사용자 추가"에 시험에 쓸 구글 계정을 모두 넣는다**(새로 가입용 · 기존 비밀번호 계정과 같은 이메일용 둘 이상). 등록 안 된 계정은 `access_denied` 로 막힌다.
   - 로그인 화면에 "확인되지 않은 앱" 경고가 뜬다 — **고급 → (앱 이름)(안전하지 않음)으로 이동** 을 누르면 진행된다. 정상이다.
   - 이 앱은 리프레시 토큰을 쓰지 않으니 "테스트 모드의 7일 만료"는 영향이 없다.
   - 서비스를 열 때 **프로덕션으로 게시**하면 누구나 로그인한다(위 세 scope 는 검증 없이 즉시). `확인 필요`(콘솔 문구는 자주 바뀐다)
4. **데이터 액세스(Data Access)** → scope 추가: `openid` · `.../auth/userinfo.email` · `.../auth/userinfo.profile` (기본으로 들어 있을 수도 있다).
5. **클라이언트(Clients) → 클라이언트 만들기**
   - 애플리케이션 유형: **웹 애플리케이션**
   - **승인된 JavaScript 원본**: `http://localhost:5173` · `https://<도메인>` (이 흐름은 코드 교환이라 원본을 쓰지 않지만 넣어 두면 해가 없다. 스킴 · 포트 포함, 끝에 `/` 없음)
   - **승인된 리디렉션 URI** (정확히 이 4개):
     ```
     http://localhost:5173/auth/callback
     http://localhost:5173/account/link-callback
     https://<도메인>/auth/callback
     https://<도메인>/account/link-callback
     ```
     규칙: https 만(localhost 는 http 허용), 와일드카드 · 프래그먼트(`#`) 불가, 끝의 `/` 하나도 다르면 `redirect_uri_mismatch`.
   - 만들면 **클라이언트 ID**(`…apps.googleusercontent.com`)와 **클라이언트 보안 비밀**이 나온다. 보안 비밀은 다시 볼 수 있는 화면도 있고 아닌 화면도 있으니 바로 복사해 둔다.
6. 값을 넣을 곳 (§6 표): `SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID` · `…_CLIENT_SECRET` · `…_REDIRECT_URI` · `…_ENABLED=true`. **클라이언트 ID 는 비밀이 아니다**(인가 URL 에 그대로 실린다), 보안 비밀은 비밀.

**코드에서 확인한 Google 의 동작** (`GoogleOAuthProvider`)
- 토큰 교환은 `application/x-www-form-urlencoded` 폼 POST. `redirect_uri` 는 **프런트가 인가 요청에 실은 값을 그대로** 받아 같이 보낸다(없으면 `…_REDIRECT_URI`). 인가 요청의 값과 다르면 구글이 `redirect_uri_mismatch`/`invalid_grant`.
- `email_verified` 는 구글이 불리언으로 준다(`true` 일 때만 "확인된 이메일"로 취급 — 기존 계정과의 병합 · 연결은 이 값이 `true` 일 때만). 구글 계정은 거의 항상 확인된 주소다.
- 구글이 4xx 로 거절하면 사용자에게는 `INVALID_AUTHORIZATION_CODE` 가 나가고, **서버 로그에 한 줄**이 남는다: `social login token exchange rejected provider=google status=400 error=redirect_uri_mismatch …` — 시험 중 실패하면 이 줄이 원인이다(비밀 · 코드는 싣지 않는다).

## 2. LINE Login 채널 (일본 사용자용)

> 이 절은 콘솔 준비와 환경변수를 적는다(모듈 쪽 설명: [auth-social-oidc](modules/auth-social-oidc.md) — LINE 프리셋). 이 절의 사실은 LINE 공식 문서에서 읽었다: <https://developers.line.biz/en/docs/line-login/getting-started/> · <https://developers.line.biz/en/docs/line-login/integrate-line-login/>.

1. <https://developers.line.biz/console/> 에 LINE 계정(또는 비즈니스 계정)으로 로그인 → **Provider 만들기** (이름 = 서비스를 운영하는 주체 이름. 사용자 동의 화면에 보인다. 나중에 못 바꾸는 값이 있으니 신중히).
2. 그 Provider 안에서 **Create a new channel → LINE Login**.
   - Region: 서비스 대상 국가(예: Japan). Channel name · description · **App type: Web app**. 이메일 주소(내 연락처). 아이콘 · 이용약관/개인정보 URL 은 선택(이메일 권한 신청 단계에서 필요해질 수 있다 — 아래 4).
3. **Basic settings 탭**: **Channel ID** (= client id), **Channel secret** (= client secret) 를 복사한다.
4. **이메일 권한 신청 (중요)**: LINE 은 기본으로 **이메일을 주지 않는다.** Channel 의 **OpenID Connect → Email address permission → Apply** 에서 약관에 동의하고 **"이메일을 수집한다는 것과 용도를 설명하는 화면의 스크린샷"을 올려** 신청한다(스크린샷 요건은 검색으로 확인한 내용 — 콘솔 화면에서 다시 확인 `확인 필요`). 승인 전에는 `scope=openid profile email` 로 요청해도 이메일이 오지 않는다.
   - 이메일을 못 받는 계정은 이 스켈레톤에서 **"주소 없는 계정"**이 된다(§4 Kakao 설명과 같다): 로그인은 되지만 메일 코드로 다시 인증할 수 없어 제공자 재동의로 다시 인증한다.
   - 참고: LINE 이 주는 이메일은 사용자가 LINE 에 등록한 주소이고, **LINE ID 토큰의 `email` 이 "확인된 주소"라는 보증이 있는지**는 공식 문서에서 찾지 못했다 → 모듈은 **LINE 이메일을 항상 미확인으로 친다**(`email-trust: NEVER` — 같은 이메일의 기존 계정에 병합하지 않고, LINE 계정은 주소 없는 계정이다). `확인 필요`
5. **LINE Login 탭 → Callback URL**: 아래 두 개를 등록한다(여러 개 가능, 줄바꿈으로 구분).
   ```
   http://localhost:5173/auth/callback        (로컬 시험 — LINE 이 http://localhost 를 받는지 콘솔에서 확인 필요. 안 받으면 터널 주소의 https 로)
   http://localhost:5173/account/link-callback
   https://<도메인>/auth/callback
   https://<도메인>/account/link-callback
   ```
6. **채널 상태**: 처음은 **Developing** — 이 채널에서는 **Provider/채널의 Admin · Tester 역할을 가진 LINE 계정만** 로그인된다. **시험할 LINE 계정을 Roles(채널 → Roles 탭)에 Tester 로 추가**한다(테스터 추가에는 LINE 계정 연결이 필요하다). 서비스를 열 때 **Published** 로 바꾼다 — 되돌릴 수 없다.
7. 환경변수 (client id = Channel ID, secret = Channel secret):
   ```bash
   SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_ID=2001234567          # Channel ID  (env:)
   SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_SECRET=...             # Channel secret (secrets:)
   SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_REDIRECT_URI=http://localhost:5173/auth/callback
   SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_SCOPES=openid,profile,email   # 콘솔의 이메일 권한이 승인된 뒤에만. 그 전에는 비워 둔다(기본 openid,profile)
   ```
   `client id` 만 있으면 켜진다(`ENABLED` 가 따로 없다). LINE 의 엔드포인트 · PKCE · nonce · ID 토큰 알고리즘(HS256/ES256)은 프리셋이 안다 — 적을 것이 없다. LINE 은 **PKCE · nonce 가 필수**이고, 프런트가 `GET /api/v1/auth/methods` 의 `pkce` · `nonce` · `authorize` 를 읽어 처리한다(react-skeleton `@skeleton/auth` 가 이미 한다).

## 3. X(Twitter) 개발자 앱

> 콘솔 준비와 환경변수(모듈 쪽 설명: [auth-social-x](modules/auth-social-x.md)). 공식 문서: <https://docs.x.com/resources/fundamentals/developer-apps> · <https://docs.x.com/resources/fundamentals/authentication/oauth-2-0/user-access-token>.

**먼저 알아 둘 것 (놀라지 않도록)**
- X 는 **OAuth 2.0 Authorization Code + PKCE** 를 쓴다: 인가 주소 `https://x.com/i/oauth2/authorize`, 토큰 주소 `https://api.x.com/2/oauth2/token`, scope `users.read tweet.read`(사용자 id · 이름을 읽는 `GET /2/users/me` 용). PKCE(`code_challenge`)는 **필수**다 — 이 레포가 지원한다(`/auth/methods` 의 `pkce: REQUIRED`, 프런트가 `code_verifier` 를 만들어 로그인 요청에 싣는다). **인가 코드는 발급 30초 안에 교환해야 한다** — 프런트는 콜백을 받자마자 백엔드를 부른다.
- **X 는 기본으로 이메일을 주지 않는다.** 콘솔에서 앱의 이메일 요청 권한을 켜고 `SKELETON_AUTH_SOCIAL_X_REQUEST_EMAIL=true` 를 주면 `users.email` 을 요청해 `confirmed_email` 이 오지만, 그것이 "확인된 주소"인지는 문서가 보증하지 않아 **병합에 쓰지 않는다**(`확인 필요`). 그래서 X 로 만든 계정은 기본적으로 **"주소 없는 계정"**이다(→ Naver 와 같은 취급).
- **API 요금**: X 는 2026-02 에 무료 · Basic · Pro 등급을 없애고 **사용량 기반(pay-per-use, 크레딧 선결제)** 으로 바꿨다고 보도되었다(읽기 요청 건당 과금 등). 로그인용 `users/me` 한두 번이 얼마인지, 시험 크레딧이 있는지는 **콘솔의 Billing 에서 확인 필요**. 요금표 근거는 비공식 블로그 몇 곳이라 신뢰도가 낮다.

1. <https://console.x.com> 에 X 계정으로 로그인 → 개발자 약관 동의 → **Create App** (이름은 전체 X 에서 유일해야 한다. 용도 설명 입력).
   - 개발용 · 운영용은 **앱을 따로** 만드는 것을 공식 문서가 권한다(콜백 URL 이 달라서도).
2. 앱 → **Settings → User authentication settings → Set up**
   - **App permissions: Read** (로그인만 하므로 읽기면 충분. "Read and write" 는 불필요).
   - **Type of App: Web App, Automated App or Bot** ← **기밀(confidential) 클라이언트**. 이 유형이면 **Client Secret** 이 발급된다(백엔드가 비밀을 들고 토큰을 교환하는 이 구조에 맞다). Native App / Single Page App 은 공개 클라이언트(비밀 없음)다.
   - **Callback URI / Redirect URL**: 규칙 — 최대 10개, **끝의 `/` 까지 정확히 일치**, 운영은 `https://`, **로컬 개발은 `http://localhost` 가 아니라 `http://127.0.0.1`** 을 쓴다(공식 문서의 로컬 규칙). 그래서:
     ```
     http://127.0.0.1:5173/auth/callback
     http://127.0.0.1:5173/account/link-callback
     https://<도메인>/auth/callback
     https://<도메인>/account/link-callback
     ```
     → **X 를 시험할 때는 브라우저로 `http://127.0.0.1:5173` 에 접속**한다(`localhost` 로 열면 프런트가 `localhost` 기준 콜백을 만들어 불일치). 그때 메일 링크 주소(`SKELETON_ACCOUNT_MAIL_LINK_BASE_URL`)도 같은 origin 이어야 한다.
   - **Website URL**: 서비스의 공개 주소(`https://<도메인>`) — 필수 칸이다.
   - **Organization name / URL, Terms of service, Privacy policy URL**: 공식 문서는 필수 여부를 적지 않았다 — 콘솔 화면에서 **칸이 있으면 채운다** (X 는 앱이 사용자에게 인가를 받을 때 이 링크를 동의 화면에 보인다. 서비스에 약관 · 개인정보처리방침 페이지가 이미 있어야 하는 이유다 — react-skeleton 의 약관 템플릿은 법적 효력이 없으니 실제 서비스는 직접 쓴다). `확인 필요`
3. 저장하면 **Client ID** 와 **Client Secret** 이 한 번만 보인다 — 바로 복사한다. (분실하면 재발급.)
4. 환경변수 (Client ID = OAuth 2.0 Client ID — OAuth 1.0a 의 API Key 가 아니다):
   ```bash
   SKELETON_AUTH_SOCIAL_X_CLIENT_ID=...                                    # (env:)
   SKELETON_AUTH_SOCIAL_X_CLIENT_SECRET=...                                # (secrets:)
   SKELETON_AUTH_SOCIAL_X_REDIRECT_URI=http://127.0.0.1:5173/auth/callback # 로컬은 127.0.0.1 — 콘솔에 등록한 값과 글자까지 같게
   # SKELETON_AUTH_SOCIAL_X_REQUEST_EMAIL=true                             # 선택 — 콘솔의 이메일 권한이 있을 때
   ```
   `client id` 만 있으면 켜진다. client id 만 있고 secret 이 없으면 기동이 실패한다(`client-secret must not be blank`).

## 4. (선택) Kakao · Naver — 오늘의 우선순위는 낮다

### Kakao
1. <https://developers.kakao.com/> → 로그인 → **내 애플리케이션 → 애플리케이션 추가** (앱 이름 · 회사명).
2. **앱 설정 → 앱 → 일반**의 **REST API 키** → 이것이 `…_KAKAO_CLIENT_ID`(32자).
3. **제품 설정 → 카카오 로그인** → **활성화 ON** → **Redirect URI 등록**: `http://localhost:5173/auth/callback` · `http://localhost:5173/account/link-callback` · `https://<도메인>/auth/callback` · `https://<도메인>/account/link-callback` (각각 글자 그대로).
4. **보안 → Client Secret**: **발급 + 활성화 상태 ON** — 공식 문서: REST API 키에는 Client Secret 기능이 **기본으로 켜져** 있어 토큰 요청에 `client_secret` 을 반드시 넣어야 한다. 이 레포는 비어 있으면 기동을 막으므로(`client secret must not be blank`) **발급해서** `…_KAKAO_CLIENT_SECRET` 에 넣는다.
5. **동의항목**: `닉네임`(선택 동의 가능) · **`카카오계정(이메일)`**. 이메일을 **필수 동의**로 두려면 **비즈 앱 전환**이 필요하다 — 개인 개발자는 "본인인증 → 앱 일반 → 개인 개발자 비즈 앱 전환(목적: 이메일 필수 동의)" 경로(검색 결과로 확인 `확인 필요`). **선택 동의**는 비즈 앱 없이 가능한 것으로 안내되지만, 사용자가 동의 화면에서 **이메일 항목을 끌 수 있다.**
6. **이메일을 못 받는 경우 (`email` 없음 / `is_email_valid=false` / `is_email_verified=false`)**: 코드(`KakaoOAuthProvider`)는 `is_email_valid` 와 `is_email_verified` 가 **둘 다 true** 일 때만 "확인된 이메일"로 본다(다른 카카오계정으로 넘어간 만료 주소는 `verified=true` · `valid=false` 로 오므로). 확인된 이메일이 아니면 계정은 **주소 없이** 만들어진다 → 이메일 로그인 · 매직 링크 · 이메일 코드가 없고, 민감한 일(이메일 변경 · 연결 해제 · 삭제)은 **카카오 동의를 다시 거치는 `socialReauth`** 로 다시 인증한다. 카카오 이메일이 있어도 아직 확인 전이면 **기존 계정과 자동 병합하지 않는다**(`409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`).
7. `redirect_uri` · 토큰 요청: 폼 POST(`application/x-www-form-urlencoded`), 에러는 `{"error":"invalid_grant","error_description":…,"error_code":"KOE320"}` 모양 — 서버 로그의 `social login token exchange rejected provider=kakao … error_code=…` 줄로 보인다. 카카오 에러 코드표: 카카오 문서 `확인 필요`.

### Naver (선택)
- <https://developers.naver.com/apps/> 에서 애플리케이션 등록 → **네이버 로그인** API, 제공 정보에 `이메일`(선택), 서비스 URL · Callback URL 등록(위와 같은 4개). Client ID / Secret 을 `…_NAVER_*` 에.
- **네이버는 확인된 이메일을 주는 보증이 없다 → 이 백엔드는 항상 "이메일 미확인"으로 다룬다**: 주소 없는 계정이 되고 병합되지 않는다.
- ⚠ **알려진 위험(확인 필요)**: 네이버 토큰 발급 API 는 `state` 파라미터를 요구하는 것으로 알고 있는데 `NaverOAuthProvider` 의 토큰 요청에는 `state` 가 없다(`OAuthProvider.fetchProfile(code, redirectUri)` 계약에 state 가 없다). 실제 네이버로 시험해 보기 전에는 동작을 장담하지 않는다. 이번 시험 범위(Google · LINE · X)가 아니라서 이 브랜치에서 고치지 않았다.

## 5. 메일 발송 — 서비스 고르기 · DNS(Cloudflare) · SMTP 값

### 5.1 먼저 이해할 것

- **From 주소 관례**: `no-reply@<도메인>`, 표시 이름 포함 `Notes <no-reply@<도메인>>`. 답장을 받고 싶으면 별도 `support@<도메인>` 을 문의 주소(`SKELETON_ACCOUNT_MAIL_BRAND_SUPPORT_ADDRESS`)로 둔다.
- **인증 3종 (스팸함으로 가지 않으려면)** — 모두 **DNS TXT/CNAME 레코드**다.
  - **SPF**: "이 도메인 이름으로 메일을 보내도 되는 서버 목록". TXT 레코드 `v=spf1 include:<보내는 서비스> ~all`.
  - **DKIM**: 메일에 디지털 서명을 붙이고, 검증용 공개키를 DNS 에 둔다. 서비스가 알려 주는 CNAME(또는 TXT) 레코드를 그대로 넣는다.
  - **DMARC**: SPF/DKIM 이 실패한 메일을 수신자가 어떻게 할지(`p=none|quarantine|reject`)와 보고서를 받을 주소. `_dmarc.<도메인>` TXT. **처음엔 `v=DMARC1; p=none; rua=mailto:<내 주소>`** 로 시작한다. Gmail · Yahoo 는 대량 발송자에게 SPF · DKIM · DMARC 를 요구한다(소량이어도 세 개 다 갖추는 것이 안전).
- **Cloudflare Email Routing 과의 관계**: Email Routing 은 **받는(전달하는) 기능 전용**이다 — `support@<도메인>` 로 온 메일을 내 Gmail 로 전달해 준다. **보내지 못한다.** 켜면 Cloudflare 가 루트 도메인에 **MX 레코드 3개**(`route1~3.mx.cloudflare.net`) 와 **SPF TXT** `v=spf1 include:_spf.mx.cloudflare.net ~all` 과 DKIM 을 자동으로 넣는다(공식: <https://developers.cloudflare.com/email-service/configuration/domains/>).
  - **같은 도메인에서 SPF 레코드는 한 개뿐**이어야 한다(`v=spf1` TXT 가 두 개면 둘 다 무효). 보내는 서비스가 **루트 도메인의 SPF 에 include 를 요구하면 한 레코드로 합친다**:
    ```
    v=spf1 include:_spf.mx.cloudflare.net include:<보내는 서비스의 include> ~all
    ```
    include 는 DNS 조회 10회 제한이 있다(공식 문서도 경고). 대부분의 서비스(Resend · SES 의 커스텀 MAIL FROM)는 **보내는 서비스 전용 서브도메인**(`send.<도메인>` 등)에 SPF/MX 를 두므로 루트 SPF 를 건드리지 않는다 — 그러면 합칠 일이 없다(서비스가 알려 주는 레코드를 **이름까지 그대로** 넣는다).
  - DMARC 의 `rua` 를 Cloudflare 로 받으려면 Email Routing 주소(`dmarc@<도메인>` → 내 Gmail)를 만들어 쓰면 편하다.

### 5.2 Cloudflare 대시보드에서 레코드 넣는 법

1. <https://dash.cloudflare.com/> → 도메인 선택 → 왼쪽 **DNS → Records → Add record**.
2. 서비스가 알려 준 대로 **Type**(TXT / CNAME / MX) · **Name** · **Content/Target** 을 넣는다.
   - **Name 은 Cloudflare 가 도메인을 자동으로 붙인다**: 서비스가 `send.example.com` 이라 적었으면 Name 칸에는 `send` 만 넣는다(전체를 넣으면 `send.example.com.example.com` 이 된다 — 흔한 실수).
3. **CNAME 은 반드시 Proxy status 를 "DNS only"(회색 구름)** 로 둔다. 주황 구름(Proxied)이면 Cloudflare 가 자기 주소로 답해 DKIM · 반송(bounce) 검증이 실패한다. (TXT · MX 는 원래 프록시 대상이 아니다.)
4. 저장 후 서비스 대시보드의 **Verify** 를 누른다. 보통 몇 분이고 길면 수 시간이다. 점검: `dig +short TXT <도메인>` · `dig +short TXT _dmarc.<도메인>` (또는 `scripts/check-real-providers.sh` 가 SPF · DMARC 를 본다).
5. Cloudflare 와 **연동 버튼(원클릭 DNS 설정)**을 주는 서비스가 있다고 알려져 있으나(`Resend` · `Cloudflare Email Service` 는 같은 계정이라 자동) 이 문서를 쓰며 공식 문서로 확인하지 못했다 → 있으면 쓰고, 없으면 위 수동 방법. `확인 필요`

### 5.3 서비스 비교 (개인 도메인 · 소량)

가격 · 한도는 **자주 바뀐다 — 가입 전에 공식 요금 페이지를 확인한다** (`확인 필요`).

| | Resend | AWS SES | Cloudflare Email Service (베타) | Google Workspace SMTP | Naver Works |
|---|---|---|---|---|---|
| 요금(문서에서 읽은 값) | 무료 월 3,000통 · 일 100통 · 도메인 3개, 유료 $20/월(5만 통)부터 (<https://resend.com/pricing>) | 종량제(1,000통에 약 $0.10 — 알려진 값, **이 문서에서 확인 못 함**) | 월 3,000통 포함 후 1,000통당 $0.35, **Workers 유료 플랜($5/월~) 필요**, 베타라 가격 잠정(검색 결과) | Workspace 유료 구독(사용자당 월 요금) 필요 | 유료 구독, 외부 서비스 발송용으로는 맞지 않을 수 있음 |
| 설정 난이도 | 가장 쉬움: 가입 → 도메인 추가 → DNS 3~4줄 → API 키 | 중간: AWS 계정 · 리전 · 도메인 검증 · **샌드박스 해제 신청** · SMTP 자격 증명 별도 생성 | 쉬움(같은 Cloudflare 계정, DNS 자동), 베타 | 쉬움(이미 Workspace 면) 하지만 SMTP 릴레이 설정이 관리자 콘솔에 있다 | 확인 필요 |
| 샌드박스 | 도메인을 검증하기 전에는 본인 주소로만(테스트 발신 `onboarding@resend.dev`) — `확인 필요` | **샌드박스: 검증된 주소/도메인으로만 발송, 24시간 200통, 초당 1통**. 운영 접근 신청은 콘솔에서(처리 약 24시간, 공식 문서) | `확인 필요` | 해당 없음(일 한도 있음 `확인 필요`) | 확인 필요 |
| SMTP | `smtp.resend.com`, 사용자 `resend`, 비밀번호 = **API 키**. 465/2465 = 암시적 SSL, 25/587/2587 = STARTTLS | `email-smtp.<리전>.amazonaws.com` 587(STARTTLS)/465(SSL)/2587. **IAM 키가 아니라 "SMTP 자격 증명"을 따로 만든다** | `smtp.mx.cloudflare.net:465`(암시적 TLS), 사용자 `api_token`, 비밀번호 = **Email Sending: Edit 권한의 API 토큰** | `smtp-relay.gmail.com`(릴레이) 또는 `smtp.gmail.com` + 앱 비밀번호 — `확인 필요` | 확인 필요 |
| 추천 용도 | **내일 시험에 가장 빠름** | 나중에 대량 · 최저가 | Cloudflare 로 올인원을 원할 때(베타 감수) | 이미 Workspace 가 있을 때 | — |

**권장**: 내일은 **Resend**(무료 · 5분) — 일 100통 한도가 시험에는 충분하다. 장기적으로 SES 나 Cloudflare Email Service 를 다시 본다.

### 5.4 이 스켈레톤이 읽는 SMTP 환경변수 (정확한 이름)

`notification-mail` 이 `spring.mail.*`(Spring 표준)과 `skeleton.notification-mail.*` 를 읽는다. 환경변수 이름은 키의 느슨한 바인딩이다.

| 환경변수 | 값 | 비고 |
|---|---|---|
| `SKELETON_NOTIFICATION_MAIL_ENABLED` | `true` | 없으면 발송기 빈이 없고 메일은 **로그로만** 간다 |
| `SKELETON_NOTIFICATION_MAIL_FROM` | `"Notes <no-reply@<도메인>>"` | 비면 기동 실패. 도메인은 위에서 DNS 인증한 것이어야 한다 |
| `SPRING_MAIL_HOST` | 예 `smtp.resend.com` | 비면 로컬 mailpit(`localhost:1025`)으로 간다 |
| `SPRING_MAIL_PORT` | 예 `587` | 465 · 587 · 2525 … |
| `SPRING_MAIL_USERNAME` | 예 `resend` | |
| `SPRING_MAIL_PASSWORD` | API 키 / SMTP 비밀번호 | **비밀** |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | `true` | 빠지면 인증 없이 보낸다 |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | 587 일 때 `true` | |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE` | 465 일 때 `true` | 암시적 SSL |
| `SKELETON_ACCOUNT_MAIL_LINK_BASE_URL` | 로컬 `http://localhost:5173`, 홈서버 `https://<도메인>` | 메일 링크가 여는 프런트 |

메일 모양(서비스 이름 · 로고 · 색 · 문의 주소 · 바닥글 · 텍스트만)은 `SKELETON_ACCOUNT_MAIL_BRAND_SERVICE_NAME` · `_LOGO_URL`(https) · `_ACCENT_COLOR`(`#2563eb`) · `_SUPPORT_ADDRESS` · `_FOOTER`, `SKELETON_ACCOUNT_MAIL_HTML_ENABLED=false` — `docs/modules/account.md`. 눈으로 보기: `./gradlew :modules:account:mailPreview` → `modules/account/build/mail-preview/index.html`.

## 6. 환경변수 한눈에

### 6.1 로컬 (`.env.local` — git 밖)

`.env.example` 을 `.env.local` 로 복사해 채운다(`.env.*` 는 `.gitignore` 에 있다 — **절대 커밋하지 않는다**). `scripts/dev-sample.sh` 가 `.env.local` 을 읽는다(`ENV_FILE=다른파일` 로 바꿀 수 있다). **빈 값은 환경에서 지워지므로 비워 두면 오늘과 똑같이 동작한다**(소셜 꺼짐, 메일은 로컬 mailpit).

형식은 셸 문법: `NAME=값`. 공백 · `<` · `&` 가 있으면 따옴표.

```bash
# --- Google (§1) -----------------------------------------------------------
SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_ENABLED=true
SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID=1234-abc.apps.googleusercontent.com
SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_SECRET=GOCSPX-...
SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_REDIRECT_URI=http://localhost:5173/auth/callback
# --- LINE (§2) — client id 만 있으면 켜진다 --------------------------------------
SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_ID=2001234567
SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_SECRET=...
SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_REDIRECT_URI=http://localhost:5173/auth/callback
# SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_SCOPES=openid,profile,email   # 콘솔의 이메일 권한 승인 뒤
# --- X (§3) — 로컬은 127.0.0.1 로 연다 (프런트 http://127.0.0.1:5173) ----------------
SKELETON_AUTH_SOCIAL_X_CLIENT_ID=...
SKELETON_AUTH_SOCIAL_X_CLIENT_SECRET=...
SKELETON_AUTH_SOCIAL_X_REDIRECT_URI=http://127.0.0.1:5173/auth/callback
# --- Kakao (선택, §4) -------------------------------------------------------
# SKELETON_AUTH_SOCIAL_PROVIDERS_KAKAO_ENABLED=true  (+ _CLIENT_ID · _CLIENT_SECRET · _REDIRECT_URI)
# --- 메일 (§5) ---------------------------------------------------------------
SKELETON_NOTIFICATION_MAIL_ENABLED=true
SKELETON_NOTIFICATION_MAIL_FROM="Notes <no-reply@<도메인>>"
SPRING_MAIL_HOST=smtp.resend.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=resend
SPRING_MAIL_PASSWORD=re_...
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
SKELETON_ACCOUNT_MAIL_BRAND_SERVICE_NAME=Notes
SKELETON_ACCOUNT_MAIL_BRAND_SUPPORT_ADDRESS=support@<도메인>
```

링크 주소 `SKELETON_ACCOUNT_MAIL_LINK_BASE_URL` 은 `application-local.yml` 에 `http://localhost:5173` 로 이미 들어 있다(X 를 `127.0.0.1` 로 시험할 때만 환경변수로 덮어쓴다).

### 6.2 프런트(react-skeleton)

**프런트 환경변수는 필요 없다.** 로그인 버튼 · client id · 콜백 주소는 `GET /api/v1/auth/methods` 가 알려 준다(`VITE_SOCIAL_<제공자>_CLIENT_ID` 는 백엔드가 client id 를 모를 때의 덮어쓰기일 뿐). 단 `scripts/dev-sample.sh` 가 프런트를 띄우려면 `../react-skeleton` 이 옆에 있어야 한다.

### 6.3 홈서버 (`deploy/app.yaml`)

- **비밀이 아닌 값은 `env:`**, **비밀은 `secrets:` 에 이름 + 값은 `data/secrets/<앱 이름>.local.env`**(0600, git 밖, 백업됨). 같은 이름을 둘 다에 쓰지 못한다. `.local.env` 에 쓴 이름이 `secrets:` 에 없으면 plan 이 거부한다. `<P>` = `env_prefix`(스켈레톤 기본 `SKELETON`).

| 이름 | 어디에 |
|---|---|
| `<P>_AUTH_SOCIAL_PROVIDERS_GOOGLE_ENABLED=true` · `_CLIENT_ID` · `_REDIRECT_URI=https://<도메인>/auth/callback` | `env:` |
| `<P>_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_SECRET` | `secrets:` + `.local.env` |
| `<P>_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_ID` · `_REDIRECT_URI=https://<도메인>/auth/callback` · (이메일 승인 뒤) `_SCOPES=openid,profile,email` | `env:` |
| `<P>_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_SECRET` | `secrets:` + `.local.env` |
| `<P>_AUTH_SOCIAL_X_CLIENT_ID` · `_REDIRECT_URI=https://<도메인>/auth/callback` · (선택) `_REQUEST_EMAIL=true` | `env:` |
| `<P>_AUTH_SOCIAL_X_CLIENT_SECRET` | `secrets:` + `.local.env` |
| `<P>_NOTIFICATION_MAIL_ENABLED=true` · `<P>_NOTIFICATION_MAIL_FROM` · `SPRING_MAIL_HOST` · `SPRING_MAIL_PORT` · `SPRING_MAIL_USERNAME` · `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true` · `…_STARTTLS_ENABLE=true` · `<P>_ACCOUNT_MAIL_LINK_BASE_URL=https://<도메인>` | `env:` |
| `SPRING_MAIL_PASSWORD` | `secrets:` + `.local.env` |

`.local.env` 예: `SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_SECRET=GOCSPX-...` / `SPRING_MAIL_PASSWORD=re_...` (줄마다 `NAME=값`). 쓰지 않는 제공자의 `secrets:` 줄은 지운다(안 지우면 무작위 쓰레기 값이 들어가 첫 사용에서 실패한다).
`SKELETON_ENV=prod` 에서는 메일 발송기가 없거나 링크 주소가 비면 **기동 실패**(가드)다 — 메일이 안 가는 서비스를 열지 않게 하는 안전장치다.

## 7. 내일의 시험

### 7.1 시작

```bash
cd projects/kotlin-skeleton            # (이 가이드가 들어 있는 레포)
cp .env.example .env.local             # 처음 한 번 — 위 §6.1 처럼 채운다
scripts/check-real-providers.sh        # ✓/✗ 로 모양 확인 (가짜 코드로 제공자 서버를 한 번 두드려 키가 맞는지도 본다)
scripts/check-real-providers.sh --send-test-mail <내 주소>   # SMTP 가 채워졌으면 시험 메일 한 통
scripts/dev-sample.sh                  # DB + 백엔드(8080) + 프런트(5173). 이 환경처럼 직접 서버가 막힌 곳은 MARINA_DIRECT=1 scripts/dev-sample.sh
scripts/check-real-providers.sh        # 백엔드가 뜬 뒤 다시: /auth/methods 와 맞는지도 본다
```

- 백엔드 기동 로그에 한 줄이 나온다 (비밀 없음):
  `account sign-in methods: password, magic_link, google; social: google(clientId=set, redirectUri=http://localhost:5173/auth/callback); mail: SMTP smtp.resend.com:587 from=Notes <no-reply@…>; html=on; link-base-url=http://localhost:5173`
  `mail: NOT SENT (log only …)` 이면 메일이 안 나가는 상태다. `clientId=MISSING` 이면 키를 못 읽은 것이다.
- 브라우저 `http://localhost:5173`(X 는 `http://127.0.0.1:5173`) → 로그인 화면에 켜 둔 제공자 버튼이 보여야 한다. 버튼이 없으면: `curl -s localhost:8080/api/v1/auth/methods` 의 `social` 을 본다(5분 캐시 — 브라우저 강력 새로고침).
- 실패했을 때 가져갈 것(공통): ① 화면의 에러 코드 ② 응답 본문의 `traceId` (`docs/errors.md`) ③ 서버 로그에서 그 `traceId` 가 있는 줄과 `social login token exchange rejected …` 줄 ④ 브라우저 주소창의 `?error=` 값.

### 7.2 눌러 볼 순서

| # | 하는 일 | 기대 결과 | 안 되면 |
|---|---|---|---|
| 1 | **이메일 가입(진짜 받은편지함)**: 가입 화면에서 `내 Gmail` 로 가입 | 202 후 인증번호 입력 화면. 메일이 와야 한다: 제목 "인증번호를 보내 드려요"(제목에 숫자 없음), 본문에 6자리 큰 코드. 코드 입력 → 로그인된 상태 | 메일이 안 옴: 스팸함 → 기동 로그의 `mail:` 줄 → 서버 로그 `account mail kind=VERIFY_CODE failed/was not accepted` → 서비스 대시보드 로그. 캡처: 그 로그 줄 + traceId |
| 2 | **Google 새 계정**: 로그아웃 → "Google 로 계속" → 계정 선택(테스트 사용자) | 구글 화면(미확인 앱 경고 → 계속) → `/auth/callback` → 로그인됨. 계정 설정에 로그인 수단 `google` | `redirect_uri_mismatch`: 로그의 `error=` 줄 + 주소창의 redirect_uri 를 §1 목록과 글자 비교. `access_denied`: 테스트 사용자 목록 |
| 3 | **Google, 같은 이메일의 비밀번호 계정이 이미 있음**: 1번으로 만든 계정의 Gmail 과 **같은 구글 계정**으로 로그아웃 상태에서 Google 로그인 | `merge-on-verified-email: true`(샘플의 선택) → 기존 계정에 연결되어 로그인 + **"로그인 수단이 추가됐어요" 알림 메일**이 도착한다. (설정이 꺼져 있으면 `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`) | 409 가 나오면 `email_verified` 가 false 인지 서버 로그/`/account/me` 확인 |
| 4 | **연결 / 해제**: 계정 설정 → 로그인 수단 → LINE 연결(또는 다른 제공자) → 연결 해제 | 연결: 제공자 화면 후 `/account/link-callback` 으로 돌아와 목록에 추가(+알림 메일). 해제: 다시 인증(비밀번호 또는 메일 코드) 후 사라짐. **마지막 수단은 해제할 수 없다(409)** | 콜백이 로그인 화면으로 가면 연결용 URI(`/account/link-callback`)가 콘솔에 빠졌다 |
| 5 | **LINE / X**: 각 제공자로 로그인 | 소셜로 처음 만든 계정은 **약관 · 개인정보 동의 화면**을 거쳐야 쓸 수 있다(`403 LEGAL.RECONSENT_REQUIRED` → 동의 → 통과). LINE 은 이메일 권한 승인 전이면 **주소 없는 계정**이 된다(정상) · X 는 항상 주소 없는 계정 | 로그의 `social login token exchange rejected` 줄 |
| 6 | **주소 없는 계정의 민감한 일**: X(또는 이메일 없는 LINE) 계정으로 계정 삭제를 눌러 본다 | 메일 코드 대신 **제공자 동의 화면을 다시 거치라**고 한다 → 돌아오면 삭제 예약 | `403 ACCOUNT.REAUTH_REQUIRED` 만 보이면 프런트가 `socialReauth` 를 안 실은 것 |
| 7 | **(선택) Kakao**: 이메일 동의 켠 경우 / 끈 경우 둘 다 | 동의 O + 확인된 이메일 → 이메일 있는 계정. 동의 X → 주소 없는 계정(위 6 과 같은 취급) | `KOE…` 코드(로그) |
| 8 | **매직 링크**: 로그아웃 → "이메일로 로그인" → 주소 입력 | 메일: 버튼 하나 + 원문 주소. 버튼 → 로그인됨(한 번만 쓸 수 있다: 같은 링크를 다시 열면 `410`) | 링크가 `localhost:5173` 이 아니면 `LINK_BASE_URL` |
| 9 | **비밀번호 재설정**: "비밀번호 찾기" → 주소 | 메일(버튼 + 원문 주소, 30분). 새 비밀번호를 정하면 다른 기기는 로그아웃 + "비밀번호가 바뀌었어요" 알림 메일 | 메일이 안 오면 1번과 같다 |
| 10 | **메일이 사람이 보기에도 괜찮은가** | 모바일 Gmail 앱 · 데스크톱에서 열어 본다: 코드 블록 · 버튼 · 어두운 모드. 스팸함에 갔으면 DNS(SPF · DKIM · DMARC) 상태를 서비스 대시보드에서 | 원본 보기("메일 원본 표시")의 `Authentication-Results: spf=pass dkim=pass dmarc=pass` 를 캡처 |

### 7.3 끝낸 뒤

- `scripts/dev.sh down`(컨테이너). `.env.local` 의 비밀을 채팅 · 커밋에 붙이지 않는다. Google 의 클라이언트 보안 비밀이 새어 나갔다면 콘솔에서 **새 비밀을 만들고 옛것을 비활성화**한다.
- 홈서버에 올릴 때는 §6.3 의 이름으로 `env:` · `secrets:` 를 채운다(`docs/deploy.md` §7).

## 8. react-skeleton 쪽 (이 레포는 건드리지 않는다)

- **바꿀 것 없음.** 백엔드가 `GET /auth/methods` 로 제공자마다 `authorize`(주소 · scope · 고정 파라미터) · `pkce` · `nonce` 를 알려 주고, react-skeleton `@skeleton/auth` 가 그것으로 PKCE(`code_verifier` 보관 · `code_challenge`)와 `nonce` 를 처리한다. 콜백은 로그인 `entry.redirectUri ?? origin + /auth/callback`, 연결 · 다시 인증은 `origin + /account/link-callback` 이다(**두 주소를 제공자 콘솔에 모두 등록**).
- X 는 로컬에서 `127.0.0.1` 로 열어야 한다(`vite --host 127.0.0.1` 또는 접속 주소만 바꾸기).
- 알려진 프런트 한계(백엔드 쪽 사실): X 인가 코드는 **30초 안에** 교환해야 한다. X 를 "연결" 대상으로 쓰는데 다른 제공자의 재동의가 앞에 끼면 30초를 넘길 수 있다.
- 프런트 환경변수 신규: 없음(`/auth/methods` 가 정한다).

## 9. 글로벌로 갈 때

한국(Kakao · Naver) → 일본 · 영어권으로 갈 때 보통 더 필요해지는 제공자:

| 제공자 | 대상 | 이메일 | 메모 |
|---|---|---|---|
| Apple | iOS 앱이 다른 소셜 로그인을 넣으면 앱스토어가 **Sign in with Apple 도 요구**한다 | 사용자가 숨길 수 있다(릴레이 주소) | 클라이언트 시크릿이 **JWT(ES256)를 직접 서명**해 만든다(6개월 만료) — 단순 "client secret 문자열"이 아니라 모듈에 서명 로직이 필요. 이번에는 제외 |
| LINE | 일본 · 대만 · 태국 | 권한 신청 후 | OIDC — §2 |
| X | 영어권 · 일본 | 없음 | OAuth 2.0 + PKCE — §3 |
| Facebook(Meta) | 영어권 | 사용자가 거부할 수 있다 | 앱 검토(App Review)와 비즈니스 인증이 까다롭다 |
| GitHub | 개발자 대상 서비스 | 공개 · 비공개 주소(별도 API) | `email` scope 로 `/user/emails` 를 읽어야 확인된 주소를 안다 |
| Microsoft | 업무 · 학교 계정 | 있음(Entra ID) | OIDC. 테넌트 선택(`common`/`organizations`/`consumers`) 결정 필요 |

**새 제공자를 더하는 법 (스켈레톤)**: ① `modules/auth-social-<이름>` 새 모듈에 `OAuthProvider` 구현(`providerId` · `fetchProfile(code, redirectUri): OAuthUserProfile` — **이메일은 제공자가 "확인했다"고 말할 때만 `emailVerified=true`**) ② `@AutoConfiguration` 에 빈을 `@ConditionalOnProperty("skeleton.auth-social.providers.<이름>.enabled")` + `@ConditionalOnMissingBean(name=…)` 로 등록하고 `META-INF/spring/…AutoConfiguration.imports` 에 적는다 ③ `AuthSocialProperties.Provider` 의 `clientId` · `clientSecret` · `redirectUri` · `*BaseUrl` · `*Path` 를 쓴다(스켈레톤 기본 주소는 모듈 안에 두고 설정으로 덮어쓸 수 있게) ④ 4xx 를 `OAuthInvalidAuthorizationCodeException` 으로 바꾸기 전에 `OAuthTokenErrorLog.rejected(...)` 를 불러 원인을 로그에 남긴다 ⑤ 소셜 로그인 수단 등록(`SocialSignInMethod`)과 `/auth/methods` 노출은 `OAuthProvider` 빈이 있으면 `account` 가 자동으로 한다 ⑥ 모듈 문서 `docs/modules/<모듈>.md` · `capabilities.json` 항목 · `docs/config/modules` 스니펫(`CapabilitiesCatalogTest` 가 붙일 객체를 보여 준다) ⑦ 프런트 `social.ts` 프리셋(§8). PKCE · 비밀 대신 서명 JWT(Apple) 같은 변형은 `OAuthProvider` 계약(`code`, `redirectUri`)을 넓혀야 하는 경우가 있다.

## 10. 확인하지 못한 것 (오프라인에서 검증할 수 없었음)

- 요금 · 무료 한도 · 샌드박스 규칙 전부(§5.3), X API 의 로그인용 최소 비용(§3), Cloudflare Email Service 의 베타 가격 · 한도.
- Kakao: 이메일 선택 동의가 동의 화면에 기본으로 보이는지, 개인 개발자 비즈 앱 전환의 현재 절차 · 에러 코드표.
- LINE: 이메일 권한 신청의 정확한 요건(스크린샷 요구는 검색 요약), `http://localhost` 콜백 허용 여부, ID 토큰 이메일의 "확인됨" 보증 여부, 웹 로그인 ID 토큰의 실제 `alg`(문서는 HS256 — 모듈은 HS256 · ES256 을 모두 받는다).
- X: 약관 · 개인정보 URL 의 필수 여부, `confirmed_email` 이 "확인된 주소"인지(모듈은 병합에 쓰지 않는다), API 등급 · 요금(로그인용 `users/me` 호출이 무료 등급에서 열려 있는지).
- Google: 기밀(confidential) 웹 클라이언트에 PKCE 를 보내도 되는지(모듈은 `SUPPORTED` 로 보낸다, 미확인).
- Naver: 토큰 요청의 `state` 요구(§4 경고).
- **Google · Kakao 토큰 서버가 올바른 키에 `invalid_grant` 를 돌려주는지**는 실제 키가 있어야 확인된다. `scripts/check-real-providers.sh` 는 틀린 키에 `invalid_client`(HTTP 401)가 오는 것까지만 이 문서를 쓰며 실제 서버로 확인했다.
