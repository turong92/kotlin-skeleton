# 진짜 제공자 시험 가이드 — 소셜 로그인(Google · LINE · X) + 진짜 메일<!-- payment-toss:start --> + 토스페이먼츠(§11)### 11.7 자동결제(빌링, 구독) 실측 기록

**2026-10-09 — 잰 것.** 같은 테스트 시크릿 키(`test_sk_`)로, **돈이 움직이지 않는 호출만**(없는 `authKey` · 없는 빌링키) 보냈다. 카드 등록 창 · 실제 발급 · 실제 과금은 **사람이 눌러야 해서 못 쟀다**(구독 화면에서 눌러 본다).

| 본 것 | 결과 | 뜻하는 것 |
|---|---|---|
| 가짜 `authKey` 로 발급 `POST /v1/billing/authorizations/issue` | `404` `NOT_FOUND_BILLING` | **이 키로 빌링 API 에 닿는다**(`NOT_SUPPORTED_METHOD` 가 아니다 — 계약 없이 테스트 키로 자동결제 호출이 열려 있다). 확정 거절로 읽힌다 |
| `customerKey` 없이 발급 | `400` `INVALID_REQUEST` ("필수 파라미터가 누락되었거나 null입니다: customerKey") | 발급은 `authKey` + `customerKey` 둘 다 필요 |
| 발급에 `Idempotency-Key` 헤더 | 거절하지 않는다 | — |
| 없는 빌링키로 승인 `POST /v1/billing/{billingKey}` + `Idempotency-Key` | `400` `INVALID_BILL_KEY_REQUEST` ("빌링키 인증이 완료되지 않았거나 유효하지 않은 빌링 거래 건입니다.") | 승인이 일어나지 않았다 → 확정 거절 |
| 같은 호출 + 헤더 `TossPayments-Test-Code: REJECT_CARD_COMPANY` | `403` `REJECT_CARD_COMPANY` | 승인 거절을 **결제 없이 재현**할 수 있다(토스가 테스트 코드를 빌링키 확인보다 먼저 적용한다) — 갱신 실패 시험에 쓴다 |
| 없는 빌링키 삭제 `DELETE /v1/billing/{billingKey}` (본문 없음 · `customerKey` 본문 있음) | 둘 다 `400` `INVALID_BILL_KEY_REQUEST` | 이미 없는 키의 삭제는 이 코드로 온다 → 장부는 "이미 지워짐"으로 읽는다. `customerKey` 가 필요한지는 못 쟀다(본문 없이 보낸다) |

**2026-10-09 (리뷰 반영) — 더 잰 것.** 같은 키로, 끝이 `=` 인 가짜 빌링키(`…Rdzk0=`)로 승인 · 삭제를 보냈다: 둘 다 `400 INVALID_BILL_KEY_REQUEST` — **모양 오류가 아니라 「없는 키」로 온다**(`=` 가 든 키도 경로로 받는다). 그래서 빌링키 모양 검사는 불투명 토큰 규칙(길이 200 · 공백 · 제어 문자 · `/` `\\` `?` `#` `%` 금지)으로 풀었다. 테스트 코드 헤더로 거절시킨 빌링 승인(`403 REJECT_CARD_COMPANY`)의 주문 번호를 `GET /v1/payments/orders/{orderId}` 로 조회하면 **`404 NOT_FOUND_PAYMENT`** — 거절 · 미도달한 승인은 조회가 끝내 null 이다. 그래서 구독은 조회 결과를 기다리지 않고 주문이 끝 상태가 되는 트랜잭션(또는 죽은 조회 작업)에서 반응한다.

**못 잰 가정** (카드 등록과 실제 발급이 있어야 한다):

| 못 잰 가정 | 틀리면 신호 | 재려면 |
|---|---|---|
| 카드 등록 창이 `successUrl` 에 `authKey` · `customerKey` 를 쿼리로 붙인다 | 귀환 화면이 `authKey` 를 못 읽는다 | 구독 화면에서 테스트 카드로 등록(인증 번호 `000000`) |
| `authKey` 의 문자 집합이 영문 · 숫자 · `-` · `_` (200자까지) · 빌링키는 불투명(문서 예시는 `=` 로 끝난다) | 카드 등록 뒤 발급이 거절된다 / 빌링키가 규칙 밖이면 그 자리에서 삭제를 시도하고 오류로 끝난다 | 첫 발급 값을 본다 |
| **같은 `authKey` 로 발급을 두 번**(겹친 `activate`) 보내면 둘째가 거절되는가, 같은 빌링키가 다시 오는가 | 둘째가 거절이면 서버는 첫째가 시작한 구독을 돌려준다. 같은 키가 오면 진 쪽이 이긴 쪽 키를 지우지 않는다(코드가 지킨다) | 카드 등록 뒤 같은 `authKey` 로 `activate` 두 번 |
| 같은 `orderId` 로 승인을 다시 보내면 무엇이 오는지 · 빌링 승인에 `Idempotency-Key` 가 먹는지 | 갱신이 두 번 과금될 수 있다 — 우리는 다시 보내지 않고 조회로 가린다(설계가 여기에 기대지 않는다) | 같은 주문으로 두 번 승인(테스트 키) |
| 빌링 승인 응답에 `card`(끝 네 자리 포함)가 온다 | 「내 구독」의 카드 표시가 비어 있다 | 첫 결제 뒤 확인 |
| 승인 지연 · 카드 최소 결제 금액 · `authKey` 유효 시간 | 승인이 30초를 넘기면 "모름" 이 잦다 · 작은 금액이 거절된다 | 눌러 보며 |
| `BILLING_DELETED` 웹훅 | 토스 쪽에서 지운 카드로 갱신이 거절된다(거절로 종료된다) | 이번 범위 밖 |

<!-- payment-toss:end -->

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
| §7 | 점검 스크립트 + 눌러 볼 순서 | 40분 |<!-- payment-toss:start -->
| §11 | 토스페이먼츠(결제 장부): 테스트 키 · 웹훅 주소 · 검증 스크립트 · **검증 기록** | 15분 + 사람의 결제 |<!-- payment-toss:end -->
<!-- payment-kakaopay:start -->
| §12 | 카카오페이 직접 연동(단건 결제): 개발자센터 앱 등록 · Secret key(dev) · 테스트 가맹점 코드 · 못 잰 가정 | 20분 + 사람의 결제 |
<!-- payment-kakaopay:end -->

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
- `email_verified` 는 구글이 불리언으로 준다(`true` 일 때만 "확인된 이메일"로 취급 — 기존 계정과의 병합 · 연결은 이 값이 `true` 일 때만). 구글 계정은 거의 항상 확인된 주소다. **다만 병합 · 새 계정의 주소는 `@gmail.com`/`googlemail.com` 이거나 Workspace(`hd` == 주소 도메인)일 때만** 쓴다(`email-trust` 기본 `AUTHORITATIVE`, 비-Gmail 주소는 주인이 바뀌어도 `email_verified: true` 가 남는다) — Gmail 주소로 시험하는 지금 계정에는 영향이 없다.
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

막히는 순서대로 적었다. `scripts/check-real-providers.sh` 가 토큰 서버에서 받은 `error_code` 를 그대로 보여 주고 아는 코드는 조치를 붙인다(아래 표).

1. <https://developers.kakao.com/> → 로그인 → **내 애플리케이션 → 애플리케이션 추가** (앱 이름 · 회사명).
2. **앱 키의 REST API 키** → 이것이 `…_KAKAO_CLIENT_ID`(32자). 네이티브 · JavaScript · Admin 키가 아니다(`KOE101`).
3. **카카오 로그인 사용 설정을 켠다 — 스위치가 두 군데일 수 있다.** 공식 문서(<https://developers.kakao.com/docs/ko/kakaologin/prerequisite>)는 "**[카카오 로그인] > [사용 설정]의 [상태]를 ON**" 이라고 쓴다. 주인이 겪은 콘솔에는 활성화 스위치가 두 곳이었다 — 한 곳만 켜면 토큰 요청이 `invalid_client` / **`KOE004`** ("To use Kakao account login, user management has to be configured.")로 거절된다. 콘솔 개편으로 메뉴 이름과 위치가 바뀔 수 있어 이 레포는 정확한 두 번째 위치를 단정하지 않는다 — **`check-real-providers.sh` 가 KOE004 를 말하면 카카오 로그인 화면의 모든 활성화(사용 설정) 스위치가 ON 인지 본다.** (OpenID Connect 활성화는 별개의 선택 기능이다 — 이 레포는 쓰지 않는다.)
4. **Redirect URI 등록** (카카오 로그인 화면): `http://localhost:5173/auth/callback` · `http://localhost:5173/account/link-callback` · `https://<도메인>/auth/callback` · `https://<도메인>/account/link-callback` (각각 글자 그대로 — 틀리면 `KOE006`).
5. **Client Secret**: 공식 문서: REST API 키에는 Client Secret 기능이 **기본으로 켜져** 있어 토큰 요청에 `client_secret` 을 넣어야 한다(없거나 틀리면 `KOE010`). 이 레포는 비어 있으면 기동을 막으므로(`client secret must not be blank`) **발급해서** `…_KAKAO_CLIENT_SECRET` 에 넣는다.
6. **동의항목 — 닉네임(`profile_nickname`)을 켠다.** 동의항목 화면의 기본 상태는 **사용 안 함**이다 — 켜지 않으면 카카오가 이름을 주지 않아 **이름 없는 계정**이 만들어진다(주인이 실제로 겪었다). 필수 동의로 설정한다(선택으로 두면 사용자가 끌 수 있다). 단 `apps/sample` 은 제공자 이름을 닉네임으로 **가져오지 않는다**(`skeleton.account.social.import-display-name: false`) — 이름 없는 계정이 되는 것은 그 앱에서는 의도다. 제공자 이름으로 닉네임을 채우는 앱(기본값 `true`)에서만 이 항목이 결과를 바꾼다.
7. **이메일(`account_email`)은 비즈 앱 뒤에만** 쓸 수 있다 — 개인 개발자는 "본인인증 → 비즈 앱으로 전환(개인 개발자 비즈 앱)" 경로가 있다(공식 문서 <https://developers.kakao.com/docs/ko/kakaologin/utilize> 의 동의항목 표는 이메일을 "비즈 앱" 대상으로 적는다; 전환 절차의 메뉴는 콘솔에서 확인). 켜지 않으면 계정은 **"이메일 없는 계정"**이 된다.
8. **이미 만들어진 계정은 콘솔을 고쳐도 이름이 자동으로 채워지지 않는다.** 이름은 **가입 때 한 번**만 쓰이고 재로그인은 계정 이름을 건드리지 않는다. 시험 계정이 이름 없이 만들어졌다면 그 계정을 지우고(계정 삭제) 다시 가입하거나, 사용자가 닉네임을 직접 정한다.
9. **이메일을 못 받는 경우 (`email` 없음 / `is_email_valid=false` / `is_email_verified=false`)**: 코드(`KakaoOAuthProvider`)는 `is_email_valid` 와 `is_email_verified` 가 **둘 다 true** 일 때만 "확인된 이메일"로 본다(다른 카카오계정으로 넘어간 만료 주소는 `verified=true` · `valid=false` 로 오므로). 확인된 이메일이 아니면 계정은 **주소 없이** 만들어진다 → 이메일 로그인 · 매직 링크 · 이메일 코드가 없고, 민감한 일(이메일 변경 · 연결 해제 · 삭제)은 **카카오 동의를 다시 거치는 `socialReauth`** 로 다시 인증한다. 카카오 이메일이 있어도 아직 확인 전이면 **기존 계정과 자동 병합하지 않는다**(`409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`).
10. 토큰 요청은 폼 POST(`application/x-www-form-urlencoded`)이고 실패하면 `{"error":"invalid_client","error_code":"KOE004","error_description":"…"}` 모양이다 — 서버 로그의 `social login token exchange rejected provider=kakao … error_code=…` 줄로도 보인다.

| `error_code` | 뜻 (출처: <https://developers.kakao.com/docs/ko/kakaologin/trouble-shooting>) | 조치 |
|---|---|---|
| `KOE004` | 카카오 로그인을 사용하도록 설정하지 않았다 | 3번 — 사용 설정 ON (스위치가 두 곳일 수 있다) |
| `KOE006` | 등록하지 않은 Redirect URI 로 요청했다 | 4번 — 글자 그대로 등록 |
| `KOE010` | Client Secret 을 쓰는 앱에서 `client_secret` 을 빠뜨렸거나 틀렸다 | 5번 — 발급한 값을 그대로 |
| `KOE101` | 잘못된 앱 키를 썼다 | 2번 — REST API 키 |
| `KOE320` | 같은 인가 코드를 두 번 썼거나 만료됐다 | 점검 스크립트의 가짜 코드에는 **이 코드가 오는 것이 정상**이다(앱 설정은 받아들여진 것) |

위 표에 없는 코드는 스크립트가 제공자 원문(`error` · `error_code` · `error_description`)만 보여 준다.

### Naver (선택)
> 이 절의 콘솔 항목(별명 · 개발 중 상태의 테스터)은 네이버 개발자센터(developers.naver.com)의 공식 문서 원문을 이 환경의 도구로는 열 수 없어 **조사 문서로 확인하지 못했다** — 아래는 주인의 시험에서 알려진 동작이고 콘솔 화면으로 확인한다.

1. <https://developers.naver.com/apps/> 에서 애플리케이션 등록 → **네이버 로그인** API 를 고른다. **서비스 URL · Callback URL**(위 Kakao 와 같은 4개)을 등록하고 Client ID / Secret 을 `…_NAVER_*` 에 넣는다.
2. **제공 정보(사용자 정보 동의 항목)에서 별명(nickname)을 필수로 설정한다** — 체크하지 않으면 프로필에 `nickname` 이 오지 않아 이름이 없다. 이름(`name`)·이메일은 선택 항목이다. 제공자 이름을 닉네임으로 가져오는 앱(기본값 `import-display-name: true`)에서만 이것이 결과를 바꾼다 — `apps/sample` 은 가져오지 않는다.
3. **애플리케이션 상태가 "개발 중"이면 멤버 관리에 등록한 계정(테스터)만 로그인된다.** 시험할 네이버 계정을 **멤버 관리**에 추가한다. 일반 사용자에게 열려면 검수를 받아 서비스 상태로 바꾼다.
4. 이미 만들어진 계정은 콘솔을 고쳐도 이름이 자동으로 채워지지 않는다(가입 때 한 번만 쓰인다) — Kakao 8번과 같다.
5. **네이버는 확인된 이메일을 주는 보증이 없다 → 이 백엔드는 항상 "이메일 미확인"으로 다룬다**: 주소 없는 계정이 되고 병합되지 않는다.
6. **토큰 요청의 `state`**: 네이버 토큰 발급 요청은 `state` 를 받는다(공식 명세 원문은 이 환경에서 못 열었다 — 널리 쓰이는 구현은 모두 `grant_type` · `client_id` · `client_secret` · `code` · `state` 를 보낸다). 이 레포는 이제 콜백이 받은 `state` 를 **로그인 · 연결 · 다시 인증 요청의 선택 필드 `state`** 로 받아(프런트가 같이 보낸다) 네이버 토큰 요청에 싣는다. 다른 제공자는 무시한다. 프런트가 `state` 를 보내지 않으면 예전처럼 싣지 않는다 — **실제 네이버로 확인(2026-10-08)**: `state` 를 싣지 않은 토큰 요청으로도 가입 · 로그인이 성공했다 — 없어도 된다. 싣는 쪽이 명세에 가깝다고 보고 그대로 둔다. `check-real-providers.sh` 는 가짜 코드 + `state` 로 토큰 서버를 한 번 두드려 응답 원문을 보여 준다(틀린 키만 ✗, 그 밖의 응답은 단정하지 않고 원문을 보인다).

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
| 3 | **Google, 같은 이메일의 비밀번호 계정이 이미 있음**: 1번으로 만든 계정의 Gmail 과 **같은 구글 계정**으로 로그아웃 상태에서 Google 로그인 | **샘플은 병합을 끈다**(모듈 기본) → `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`: 비밀번호로 로그인해 설정에서 Google 을 직접 연결한다. (`merge-on-verified-email: true` 로 켠 앱에서는 Gmail 이므로 기존 계정에 연결되어 로그인 + **"로그인 수단이 추가됐어요" 알림 메일**) | 병합을 켠 앱에서 409 가 나오면 `email_verified` 가 false 이거나 비-Gmail 주소(권위자 아님)인지 확인 |
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
- Naver: 토큰 요청의 `state` — 요청 필드 `state` 로 받아 싣는다(§4 Naver 6번). 실제 네이버는 없어도 받아 준다(2026-10-08 확인).
- **Google · Kakao 토큰 서버가 올바른 키에 `invalid_grant` 를 돌려주는지**는 실제 키가 있어야 확인된다. `scripts/check-real-providers.sh` 는 틀린 키에 `invalid_client`(HTTP 401)가 오는 것까지만 이 문서를 쓰며 실제 서버로 확인했다.

<!-- payment-toss:start -->
## 11. 토스페이먼츠 (결제 장부)

결제 장부(`payment` · `payment-jdbc` · `payment-toss`)가 토스에 기대는 가정을 **진짜 토스(테스트 키)로 재 보는** 절차와 그 기록이다. 모듈 설명은 [docs/modules/payment-toss.md](modules/payment-toss.md), 웹훅 규칙은 [docs/payment-http-contract.md](payment-http-contract.md) §8.

### 11.1 키 두 개 — 어디에 두나

토스페이먼츠 개발자센터에서 **결제창(API 개별 연동) 테스트 키**를 받는다 — 시크릿 키 `test_sk_…` 와 같은 묶음의 클라이언트 키 `test_ck_…`. 결제위젯 키(`test_gsk_…` · `test_gck_…`)는 쓰지 않는다. 둘 다 `.env.local`(git 밖, §6.1)에 둔다.

| 값 | 환경 변수 | 비밀? |
|---|---|---|
| 켜기 | `SKELETON_PAYMENT_TOSS_ENABLED=true` | — |
| 시크릿 키 `test_sk_…` | **`SKELETON_PAYMENT_TOSS_SECRET_KEY`** (모듈의 이름 — 모든 앱이 읽는다. 찍은 프로젝트는 `SKELETON_` 자리가 자기 접두사다) · 샘플 · 워크벤치만 벤더 별칭 `TOSS_PAYMENTS_SECRET_KEY` 도 읽는다 | **비밀** — 서버만 안다. 채팅 · 커밋 · 로그에 붙이지 않는다 |
| 클라이언트 키 `test_ck_…` | **`SKELETON_PAYMENT_TOSS_CLIENT_KEY`** · 샘플 · 워크벤치만 별칭 `TOSS_PAYMENTS_CLIENT_KEY` | 비밀 아님 — 결제창을 여는 브라우저로 나간다. 시크릿 키와 **같은 묶음** |

- **모듈 이름을 권한다.** `--modules payment,payment-jdbc,payment-toss` 로 찍은 스타터(`apps/api`)의 `application.yml` 에는 별칭 줄이 없다 — 별칭만 채우면 스타터 앱에는 키가 없다. 샘플 · 워크벤치는 두 이름을 다 읽고 **둘 다 있으면 모듈 이름이 이긴다**(`apps/sample` 의 `application.yml`). 점검 스크립트(11.3)도 같은 순서로 고르고, 두 이름에 **다른 값**이 있으면 `✗` 로 멈춘다.

- 두 키의 모드(`test` · `live`)가 다르거나 종류가 바뀌면(`ck` 자리에 `sk`) 기동이 실패한다. 로컬 · 시험 환경에서 `live_…` 키도 기동 실패다.
- 클라이언트 키가 없으면 기동은 되지만 결제창을 열 수 없다(`GET /api/v1/payments/config` 가 `available: false`). **서버 쪽 시험(아래 11.3)은 시크릿 키만으로 된다.**
- 홈서버에서는 시크릿 키와 웹훅 비밀 조각을 `secrets:` 로, 클라이언트 키는 `env:` 로 둔다(§6.3 · [docs/deploy.md](deploy.md) §7).

### 11.2 웹훅 주소 등록 (주소 비밀 조각)

토스의 결제 웹훅은 서명이 없다 — **주소 끝의 비밀 조각이 진위의 전부**다.

1. 비밀 조각을 만든다: 24자 이상, 영문 · 숫자 · `-` `.` `_` `~`, 서로 다른 글자 8개 이상 (예 `openssl rand -hex 24`). 환경 변수 `SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN` 에 넣는다 — 비면 서명 없는 결제사의 웹훅 경로는 늘 404 이고, stage · prod 에서 비었거나 약하면 기동이 실패한다.
2. 개발자센터 → 웹훅에서 주소를 등록한다: `https://<도메인>/api/v1/payments/webhooks/toss/<비밀 조각>`, 이벤트 `PAYMENT_STATUS_CHANGED`. 비밀 조각을 우리 로그에는 남기지 않는다(요청 로그가 그 자리를 가린다). 개발자센터 화면과 Caddy · Tunnel 접근 로그에는 남을 수 있다.
3. 로컬에는 공개 주소가 없다(포트포워딩 금지). 토스가 보내는 진짜 웹훅은 도메인이 있는 환경(홈서버)에서만 받고, 로컬에서는 11.5 의 스크립트로 같은 모양을 손으로 넣는다.

### 11.3 검증 스크립트 — 시크릿 키만으로 재는 것

```bash
scripts/check-real-providers.sh --toss-only --env-file <시크릿 키가 든 파일>     # 토스 구역만. 호출 여섯 번
scripts/check-real-providers.sh                                                  # 소셜 · 메일 점검 뒤에 토스 구역도 (키가 있을 때만)
```

**환경 파일은 `source` 된다** — 그 파일의 셸 코드가 실행되고 값이 자식 프로세스 환경에 실린다. 믿는 파일만 준다.

보내는 것은 **돈이 움직이지 않는 호출뿐**이다: 없는 주문 번호 조회 · 없는 결제 키 조회 · 없는 결제 취소(같은 `Idempotency-Key` 로 두 번) · 틀린 키로 조회 한 번 · **`TossPayments-Test-Code: NOT_ALLOWED_PARTIAL_REFUND` 헤더를 붙인 없는 결제 취소 한 번**(토스는 테스트 시크릿 키에서 이 헤더의 오류를 그대로 돌려준다 — 토스가 그것을 결제 존재 확인보다 먼저 적용하므로 결제 없이도 잰다, 아래 11.6. 응답 코드가 장부의 확정 거절 목록 `DEFINITIVE_CODES` 와 맞는지 본다). 응답을 **장부가 읽는 대로** 해석해 `✓` / `✗` 로 말한다 — 없는 결제 조회는 `404 NOT_FOUND_PAYMENT` 여야 장부가 "결제사가 모른다"로 읽고, 취소의 같은 코드는 확정 거절로 읽힌다(그 밖의 응답은 장부가 "모름"으로 읽어 다시 해 본다). 키 모양(시크릿 · 클라이언트 · 모드 · 결제위젯 키)도 먼저 본다. `live_` 키면 토스를 부르지 않고 `✗` 로 멈춘다.

- **키는 출력되지 않는다**: 모양 접두사와 길이만 찍는다. 인증 헤더는 0600 임시 파일에서 `curl -H @파일` 로 읽혀 프로세스 인자에 남지 않고, 응답 원문을 찍을 때는 키와 그 base64 를 `***` 로 가린다. `set -x` 를 쓰지 않는다.
- **종료 코드**: `0` 문제 없음 · `1` `✗` 가 하나라도 있음 · `3` **`--toss-only` 로 토스만 쟀고** 문제는 없지만 클라이언트 키가 없어 결제 승인 단계를 건너뜀 (사람이 결제창을 열어야 재는 것 — 11.4). 소셜 · 메일 점검 뒤에 딸려 도는 평소 점검은 클라이언트 키가 없다는 이유만으로는 `0` 이다(기존 사용처가 깨지지 않게) — 건너뛴 줄 `건너뜀` 만 분명히 찍는다.
- 키 이름은 **모듈 이름(`SKELETON_PAYMENT_TOSS_*_KEY`)이 먼저**, 벤더 별칭(`TOSS_PAYMENTS_*_KEY`)은 그다음이다(11.1). 키는 있는데 `SKELETON_PAYMENT_TOSS_ENABLED=true` 가 없으면 「앱은 토스가 꺼진 채 시작한다」고 알린다.
- 환경 파일은 `--env-file`(또는 `ENV_FILE`)로 준다. `CHECK_TOSS_BASE_URL` 로 주소를 바꿀 수 있다(스크립트 자신의 시험용 — `--self-test` 는 가짜 토스로 돈다; 켜져 있으면 출력 첫머리에 「가짜 서버 대상」이 찍힌다). `CHECK_TOSS_TIMEOUT_SEC` 로 호출의 시간 제한(기본 30초)을 바꾼다.

### 11.4 사람이 하는 단계 — 결제창을 열어야 재는 것

클라이언트 키까지 있을 때, 샘플을 띄우고(`scripts/dev-sample.sh`) 응원 화면에서 결제창을 연다. 결제창에서 **테스트 결제 정보**(개발자센터가 안내하는 테스트용 카드 입력 방식)를 사람이 입력해야 한다. 한 번 승인한 뒤 11.6 의 못 잰 가정을 하나씩 본다: 받은 결제 키의 모양 · 승인 응답의 `cancels` · 부분 환불 뒤 응답 · 웹훅 도착 여부. 서버 로그의 `payment webhook result=` 줄과 운영자 수신함(`GET /api/v1/admin/payments/webhook-events`)이 기록 장소다.

### 11.5 웹훅을 손으로 넣기 — `scripts/payment-webhook.sh`

```bash
scripts/payment-webhook.sh --env-file .env.local --url http://localhost:<포트> --payment-key <결제 키> --order-id <우리 주문 id>
```

`--env-file` 은 `.env.example` 모양의 줄(줄 끝 주석 · 따옴표 · `export ` · CRLF)을 읽는다 — 따옴표 안의 `#` 은 값이다. 그 이름의 줄이 없으면 환경 변수의 비밀 조각을 쓰고 그 사실을 한 줄 알린다(값은 찍지 않는다). 옵션의 값이 빠지거나 인자가 틀리면 종료 코드 `2` 다.

서버 주소는 늘 `--url` 로 준다(기본값이 없다 — 어느 서버에 넣는지는 명시한다). 토스 `PAYMENT_STATUS_CHANGED` 모양의 본문(`data.paymentKey` · `data.orderId`)을 수신함 주소로 보낸다. 손으로 확인하는 것:

| 하고 싶은 것 | 옵션 | 기대 |
|---|---|---|
| 정상 수신 | (없음) | `200`, 서버 로그 `payment webhook result=STORED`. 처리 작업이 **토스에 조회**하고 `converge` 한다 |
| 중복 | `--repeat 2` | 둘 다 `200`, 로그 `STORED` 다음 `DUPLICATE`(같은 전달 id) |
| 모르는 주문 | 없는 `--order-id` | `200`(구별하지 않는다), 로그 `UNKNOWN_ORDER`, 수신함에 줄이 없다 |
| 비밀 조각 없이 보냄 | `--no-token` | 조각을 요구하는 서버는 `404`(이것이 통과다). 조각이 없는데 이 옵션도 없으면 보내지 않고 `2` 로 멈춘다 |
| 비밀 조각이 틀림 | `--wrong-token` | `404 PAYMENT.WEBHOOK_NOT_FOUND`, 로그에 줄이 없다 (404 가 통과다) |
| **본문 불신** | `--decoy-status CANCELED` | 본문이 거짓 상태를 말해도 주문은 **토스의 조회 결과대로** 전이한다 — 본문의 `status` 는 읽지 않는다 |
| 다루지 않는 이벤트 | `--event DEPOSIT_CALLBACK` | `200`, 로그 `UNSUPPORTED`, 저장하지 않는다 |

비밀 조각은 **`--env-file`** 로 앱이 읽는 이름(`SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN`)의 줄에서 읽는 것을 권한다 — 이 스크립트는 그 파일을 셸로 실행하지 않고 그 한 줄만 꺼낸다(11.3 의 `check-real-providers.sh` 는 파일을 `source` 한다는 점이 다르다). 값을 명령줄에 쓰면 셸 이력에 남고(`PAYMENT_WEBHOOK_TOKEN=<값> scripts/…` 도 같다), `--token` 은 프로세스 목록에도 남는다. 조각을 아예 안 주면 스크립트는 **멈춘다**(종료 코드 2) — 조각 없이 보내 `404` 를 보려는 것이면 `--no-token` 을 명시한다(기대가 404 로 바뀐다). 스크립트는 조각을 출력하지 않고, curl 의 주소도 인자가 아닌 stdin 설정으로 넘긴다. 이 스크립트가 만드는 본문 모양은 **가정**이다(11.6 의 「본문의 결제 키 · 주문 번호 위치」).

### 11.6 검증 기록

**2026-10-09 — 잰 것.** 테스트 시크릿 키(`test_sk_`, 36자; 클라이언트 키 없음) 한 개로 `check-real-providers.sh --toss-only` 를 한 번 돌렸다(종료 코드 3 = 문제 없음 + 결제 승인 단계 건너뜀). 진짜 토스로 간 호출은 그 다섯 번과, 응답 헤더를 보려고 같은 모양의 조회를 직접 한 번 더 한 것(404) — 합쳐 여섯 번, 모두 조회 · 없는 결제의 취소뿐이고 돈은 움직이지 않았다.

| 본 것 | 결과 | 장부에 뜻하는 것 |
|---|---|---|
| 없는 주문 번호 조회 `GET /v1/payments/orders/{orderId}` | `404` `NOT_FOUND_PAYMENT`, 170ms. 본문은 `code` · `message` 두 칸뿐 | 장부의 조회가 `null`("결제사가 모른다")로 읽는 응답과 일치 |
| 없는 결제 키 조회 `GET /v1/payments/{paymentKey}` | `404` `NOT_FOUND_PAYMENT`, 62ms | 같음 |
| 없는 결제의 취소 `POST /v1/payments/{paymentKey}/cancel` + `Idempotency-Key` | `404` `NOT_FOUND_PAYMENT`, 124ms — 멱등 키 헤더를 붙여도 거절하지 않는다 | 확정 거절(`DEFINITIVE_CODES`)로 읽힌다 |
| 같은 멱등 키로 같은 취소를 다시 | 같은 `404` `NOT_FOUND_PAYMENT` | **오류 경로뿐**이다 — 성공한 취소의 재응답 본문은 못 쟀다(11.6 못 잰 것) |
| 틀린 시크릿 키로 조회 | `401` `UNAUTHORIZED_KEY`, 49ms | 확정 거절로 읽히는 코드(조회에서는 확정으로 쓰지 않는다) |
| 시크릿 키 모양 | `test_sk_` + 길이 36 — 모양 가드 통과 | — |
| 응답 시간 | 가장 느린 것 170ms (조회 · 취소 여섯 번) | 기본 응답 제한 5초 안. **승인 호출의 지연은 아니다** |
| 추적 번호 | 오류 응답의 추적 번호는 **헤더 `x-tosspayments-trace-id`** 로만 온다(본문에 `traceId` 없음) | 오류 매퍼가 찾던 헤더 이름들에 없던 이름이었다 → `TossPaymentErrorMapper.TOSS_TRACE_HEADERS` 의 첫 이름으로 더했다(시험 있음) |

스크립트 자신의 시험(`--self-test`)은 가짜 토스로 돌아 위 해석 규칙 · 종료 코드 · 키가 출력에 안 나오는 것을 본다. `scripts/payment-webhook.sh` 는 가짜 수신함으로만 시험했고 **실제 서버에 대고는 돌리지 않았다**(QA 몫).

**2026-10-09 (리뷰 1회차 반영) — 테스트 코드 헤더(`TossPayments-Test-Code`).** 같은 테스트 시크릿 키(`test_sk_`, 36자)로, 없는 결제 키에 대한 취소 `POST /v1/payments/{paymentKey}/cancel` 에 `Idempotency-Key` 와 `TossPayments-Test-Code: NOT_ALLOWED_PARTIAL_REFUND` 를 붙여 **한 번** 보냈다. 결과: `HTTP 403`, 본문 `code` = `NOT_ALLOWED_PARTIAL_REFUND`(헤더에 넣은 코드 그대로), 85ms — **`NOT_FOUND_PAYMENT` 가 아니었다.** 즉 토스는 테스트 코드를 **결제 존재 확인보다 먼저** 적용한다. 그래서 취소 쪽 재현은 시크릿 키만으로, 결제 없이 잴 수 있다 → 스크립트의 정식 항목이 되었다(`check-real-providers.sh` 의 「테스트 코드 헤더를 붙인 취소」: 4xx 이고 응답 코드가 보낸 코드와 같아야 `✓`, 없는 결제의 `404 NOT_FOUND_PAYMENT` 가 먼저 나오면 `✗`). 그 코드는 장부의 확정 거절 목록(`TossPaymentErrorMapper.DEFINITIVE_CODES`)에 있고 상태가 4xx 라 장부는 이것을 확정 거절로 읽는다(스크립트의 `--self-test` 가 목록에 있는지 대 본다). 같은 실행의 `--toss-only` 전체 한 번(호출 여섯 번: 조회 둘 · 취소 둘 · 틀린 키 조회 · 테스트 코드 취소)도 모두 위 표대로였다. 진짜 토스로 간 호출은 이 직접 한 번과 스크립트 한 번의 일곱 번뿐이고 돈은 움직이지 않았다. **아직 못 잰 것**: 같은 헤더로 **승인**(`/confirm`) 오류가 재현되는지 — 승인 호출은 결제 키 · 금액이 있어야 하고 돈이 움직일 수 있는 호출이라 이 스크립트에 넣지 않았다(아래 못 잰 가정).

**못 잰 가정** — 아래는 결제 승인(결제창 + 사람 + 클라이언트 키)이나 도메인이 있는 환경이 있어야 재는 것이라 이번에 **재지 못했다.** 각각 틀리면 무엇이 신호인지 적는다.

| 못 잰 가정 | 틀리면 신호 | 재려면 |
|---|---|---|
| `failUrl` 에 쿼리(`?order=…`)가 붙어 있어도 토스가 `code` · `message` 를 `?`/`&` 로 제대로 덧붙인다 | 결제 실패 귀환 화면이 `code` · `message` 를 못 읽거나 주소가 `?…?code=` 로 깨진다 | 결제창에서 일부러 실패(취소 아님)시켜 돌아온 주소를 본다 |
| 사용자가 결제창을 닫을 때(`PAY_PROCESS_CANCELED`) 토스가 `failUrl` 에 `orderId` 를 싣는다 | 귀환 화면이 우리 주문 id(`order` 쿼리)만으로 찾으므로, 없으면 어느 주문인지 몰라 「주문을 못 찾았어요」 류 화면이 나온다 | 결제창을 연 채 닫고 돌아온 주소를 본다 |
| 토스의 웹훅 응답 제한은 10초이고, 그 안에 `200` 이 없으면 다시 보낸다(재전송 횟수 · 간격은 모른다) — 코드 주석의 주장이다 | 개발자센터 웹훅 전송 내역에 실패 · 재시도가 쌓이거나, 로그에 같은 일의 `DUPLICATE` · `REQUEUED` 가 예상보다 많다. (수신은 DB 쓰기 한 번이고 토스를 부르지 않는다) | 도메인이 있는 환경에서 전송 내역을 본다 |
| 조회의 상점 단위 호출 한도와 그때의 응답(상태 · 코드) | 조회가 `429` 등 모르는 코드로 돌아와 로그에 `payment lookup failed … exception=`, 주문이 `CONFIRM_UNRESOLVED` 로 표시된다 | 여러 주문의 조회를 한꺼번에 돌려 본다(테스트 키로) |
| 등록한 웹훅 주소를 토스가 **변형 없이** 그대로 보낸다(비밀 조각이 든 경로 · 끝 `/`) | 토스가 보낸 웹훅이 `404` 로 돌아와(전송 내역) 로그에 `payment webhook result=` 줄이 없다 | 주소를 등록하고 결제 뒤 전송 내역을 본다 |
| `tosspayments-webhook-transmission-id` 헤더가 `PAYMENT_STATUS_CHANGED` 에도 오고 **재전송에서 같은 값**이다 | 같은 일의 수신함 줄이 여럿 생긴다(처리는 늘 조회라 결과는 같다). 헤더가 없으면 본문 sha256 이 이벤트 키가 된다 | 같은 결제의 웹훅이 두 번 올 때 두 줄의 키를 비교한다 |
| 본문의 결제 키 · 주문 번호가 `data.paymentKey` · `data.orderId` 에 있다 | 수신마다 `payment webhook result=UNKNOWN_ORDER` 로그(주문 번호를 못 꺼냄) — 또는 결제 키를 못 꺼내 `401 PAYMENT.WEBHOOK_REJECTED`. 어느 쪽이든 신호는 「실제 결제 뒤에도 수신 줄이 `STORED` 가 아니다」 | 결제 뒤 도착한 웹훅 본문을 본다(`docs` 의 예시가 아니라 실물) |
| **테스트 키로도** 결제 웹훅이 오는지 | 결제를 끝내도 로그에 `payment webhook result=` 줄이 하나도 없다(전송 내역에도 없다) — 그러면 웹훅 경로는 테스트 키로 시험할 수 없다 | 테스트 결제 한 번 뒤 로그 · 전송 내역 |
| 개발자센터에서 취소하면 어떤 이벤트가 오는지 | 콘솔에서 취소했는데 장부의 주문이 안 바뀌고 로그에 `result=UNSUPPORTED`(또는 줄 없음) — 장부는 `PAYMENT_STATUS_CHANGED` 만 받으므로 그때는 운영자 `sync`(결제사와 맞추기)로만 들어온다 | 콘솔에서 테스트 결제를 취소하고 로그를 본다 |
| `cancels[].cancelStatus` 가 완료 때 `DONE` 이다(다른 값이면 그 취소를 장부가 세지 않는다) · `cancels[].transactionKey` 가 늘 온다 | 환불이 토스에서는 됐는데 장부가 `NOT_APPLIED` 로 정산하거나, 응답을 못 읽어 환불이 모름으로 끝나 정산 작업이 반복된다 | 부분 환불 한 번 뒤 조회 응답의 `cancels` |
| 할인 · 포인트가 낀 결제에서도 `balanceAmount = totalAmount - Σcancels` 가 성립한다 | 환불 전 확인이 **매번** 검토 표시를 찍어 환불이 막힌다 | 포인트 · 할인이 낀 테스트 결제(가능하면) |
| `lastTransactionKey` 가 이번 취소의 거래 키다(환불 응답에서 이번 취소를 가려내는 근거) | 환불은 성공했는데 환불 줄에 `providerRefundId` 가 안 박히고 정산 작업이 조회로 넘겨 맞춘다(돈은 안전, 지연만) | 부분 환불 응답의 `lastTransactionKey` 와 `cancels[]` 를 대 본다 |
| **같은 멱등 키로 성공한 취소를 다시** 보내면 같은 본문으로 재응답한다(우리는 다시 보내지 않지만 중간 프록시의 재전송에 대비해 안다) | 두 번째 응답이 오류(`ALREADY_CANCELED_PAYMENT` 등)면 장부가 그것을 모름으로 읽고 조회로 맞춘다 — 안전하나 로그에 오류가 남는다 | 같은 키로 취소를 두 번 |
| 결제 키의 문자 집합이 영문 · 숫자 · `-` · `_` (최대 200자) | **진짜 토스의 모든 결제가 `400` 으로 거절된다**(승인 선점 전에 장부가 거절) — 가장 아픈 가정 | 받은 결제 키를 `[A-Za-z0-9_-]{1,200}` 에 대 본다 |
| 결제 수단 값 · 카드사 코드 대응표(`method`, `issuerCode`; 문화상품권류 3개는 문서 기억에 기댄 추정) · 카드 번호 끝 네 글자의 모양 | 화면의 수단 라벨이 코드 그대로이거나 비고, 카드 표시가 `****` 로 끝난다 | 결제 응답의 `method` · `card` |
| 결제창만 열고 인증하지 않은 주문 번호의 조회(404 인가), 인증만 하고 승인하지 않은 결제의 `status`, `EXPIRED` 가 되는 데 걸리는 시간 | 만료 · 조회 작업이 열린 주문을 잘못 읽는다(없음 ↔ 진행 중) | 결제창을 열고 방치 |
| `TossPayments-Test-Code` 로 **승인** 오류가 재현되는지(취소 쪽은 위 2026-10-09 기록으로 쟀다), 승인 호출의 실제 지연 · `response-timeout: 30s` 가 맞는지 | 승인 호출이 30초 전에 끊겨 "모름" 이 잦다. 테스트 코드를 붙여도 승인 오류가 안 돌아오면 거절 분류(`DECLINED`)를 시험 환경에서 재현할 수 없다 | 결제 승인과 함께(결제창에서 얻은 `paymentKey` · `orderId` · `amount` 로 승인을 테스트 코드와 함께 부른다) |
| 시크릿 키가 가입 뒤 받은 **내 키**인가(문서용 공개 키는 웹훅 · 결제 내역 확인이 안 된다) | 결제 내역이 개발자센터에 안 보인다 | 개발자센터에서 확인 — 이번 호출은 두 경우 모두 같은 응답이라 가리지 못한다 |
| 토스 SDK v2 `requestPayment` 의 **귀환 값**(스펙 §16) — 리다이렉트 방식에서 무엇을 돌려주는지, 비회원 상수(`ANONYMOUS`)를 스크립트 판에서 어떻게 꺼내는지 | 결제창을 연 뒤 프론트가 `requestPayment` 의 반환 값(Promise 결과)에 기대고 있으면 `successUrl` 로 돌아온 뒤에도 값이 없거나 호출이 예외로 끝난다 — 결제창은 열리는데 돌아온 화면이 「확인 중」에 머문다 | 샘플에서 결제창을 열고 반환 값 · 콘솔 오류를 본다 |
| 배포 경로(Cloudflare Tunnel · Caddy 뒤)에서 웹훅 본문이 그대로 닿는지 · Caddy 접근 로그에 경로(비밀 조각)가 남는지 · 모바일에서 `successUrl` 로 돌아오는지 | 개발자센터 전송 내역에 `404` · 본문 변형(11.2 의 3번), Caddy 로그에 `/webhooks/toss/<조각>` 이 그대로 보임(조각 노출), 모바일 · 앱 내 브라우저에서 결제 뒤 귀환 화면이 안 열림 | QA / 홈서버 |
<!-- payment-toss:end -->

<!-- payment-kakaopay:start -->
## 12. 카카오페이 직접 연동 (단건 결제)

토스 계약 없이 카카오페이 계약만 있는 사업자를 위한 별도 결제사(`payment-kakaopay`)가 기대는 가정을 **진짜 카카오페이 테스트 환경으로 재 보는** 절차와 목록이다. 모듈 설명은 [docs/modules/payment-kakaopay.md](modules/payment-kakaopay.md), HTTP 계약은 [docs/payment-http-contract.md](payment-http-contract.md). 공식 문서: <https://developers.kakaopay.com/docs/payment/online/single-payment> · [주문 조회](https://developers.kakaopay.com/docs/payment/online/payment-detail) · [결제 취소](https://developers.kakaopay.com/docs/payment/online/cancellation) (2026-10-16 에 본문을 읽었다).

### 12.1 준비 - 계약 없이 테스트까지

1. 카카오 개발자(카카오페이 개발자센터, <https://developers.kakaopay.com>)에 **비즈니스 계정**으로 가입하고 **애플리케이션을 등록**한다. 로그인 계정이 비즈니스 계정이어야 한다는 것까지 문서에서 읽었고, 비즈니스 계정에 사업자등록이 필요한지는 **못 확인했다**.
2. 앱 설정의 「사용 API」에 온라인 결제를 켜고 「플랫폼 > Web」에 서비스 도메인을 등록한다(와일드카드 가능, 최대 10개). 로컬은 `http://localhost:5173` 을 넣어 보고, 막히면 그 줄이 거절 신호다(못 잰 가정).
3. 앱의 **Secret key(dev)** 를 복사한다. 테스트 결제는 가맹점 코드 **`TC0ONETIME`**(단건) + Secret key(dev) 로 부른다 - **제휴(CID 발급) 전에도 된다**고 문서가 말한다. 운영은 제휴 뒤 발급되는 가맹점 코드와 운영 Secret key.
4. 값을 넣는다(샘플 앱):

```
SKELETON_PAYMENT_KAKAOPAY_ENABLED=true
SKELETON_PAYMENT_KAKAOPAY_SECRET_KEY=<Secret key(dev)>      # 비밀
SKELETON_PAYMENT_KAKAOPAY_CID=TC0ONETIME
SKELETON_PAYMENT_KAKAOPAY_RETURN_URL=http://localhost:5173/payments/return
```

테스트 가맹점 코드(`TC0ONETIME`)는 stage · prod 에서 기동 실패, 실결제 코드는 로컬 계열에서 기동 실패한다(키는 모양으로 테스트와 실결제를 가르지 않아서 **가맹점 코드가 가른다**). 토스도 켜져 있으면 결제 화면에 「결제 방법」 고르기가 생긴다(같은 나라의 결제사가 둘 이상이므로).

### 12.2 눌러 보기 (사람이 하는 단계)

샘플을 띄우고(`scripts/dev-sample.sh`) 응원하기 → 카카오페이를 고르고 결제한다. 결제창(PC 는 QR · 카카오톡 메시지, 모바일은 카카오톡)에서 인증하면 `RETURN_URL` 로 돌아와 「결제됨」이 된다. 한 번 승인한 뒤 12.3 의 못 잰 가정을 하나씩 본다. 카카오페이는 **웹훅이 없다**고 읽었다(문서에서 찾지 못했다) - 로그의 `payment confirm` · `KakaoPay approve` 줄과 운영자 화면이 기록 장소다.

### 12.3 못 잰 가정 (진짜 키가 있어야 잰다)

| 못 잰 가정 | 틀리면 신호 | 재려면 |
|---|---|---|
| 돌아온 주소가 `approval_url` 에 이미 있는 쿼리가 있어도 `&pg_token=…` 으로 붙는다 | 돌아온 화면의 주소에 `pg_token` 이 없거나 `?` 가 두 번 | 결제 한 번 뒤 주소창을 본다 |
| `pg_token` 의 문자 집합이 영문 · 숫자 · `_` `.` `~` `-` (200자까지) · 거래 번호(`tid`)는 영문 · 숫자 · `-` `_` (50자까지) | 모양이 틀린 토큰은 **선점 전에** `400`(주문은 `PENDING` 그대로)이라 실제 토큰이 규칙 밖이면 모든 결제가 400 으로 막힌다 - 이 신호가 보이면 규칙을 넓힌다. 모양만 맞는 위조 · 남의 토큰은 카카오페이가 `-780` 으로 거절한다(아래) | 첫 결제의 `tid` · `pg_token` 을 본다 |
| 승인 실패(`-780`)의 `extras.method_result_code` 중 **카드 거절 · 잔액 부족 · 위조 토큰** 코드 | 지금은 -780 을 전부 모름으로 읽는다: 주문은 `CONFIRMING`(202)이 되고 첫 조회가 `reconcile.first-delay`(기본 60초) 뒤에 돈다. 카카오페이가 그 결제를 `FAIL_PAYMENT`/`QUIT_PAYMENT` 로 바꿔 주면 그 조회에서 `FAILED` 로 닫힌다. **바꿔 주지 않으면**(`AUTH_PASSWORD` 로 남는 위조 토큰 등 - 못 쟀다) 조회가 진행 중으로 읽어 큐의 기본 백오프(30초 · 2배 · 최대 1시간, 12번)로 약 4시간 되묻다가 `CONFIRM_UNRESOLVED` 로 사람이 볼 목록에 오른다 - 자동으로 `FAILED` 가 되지는 않는다 | 한도 초과 · 잔액 부족 · 틀린 토큰을 일부러 만들어 코드와 조회 상태를 적는다. 확정 거절이 분명한 코드는 `KakaoPayErrorMapper.DEFINITIVE_CODES` 에 더한다 |
| 구매자가 인증을 마친 뒤 돌아오지 않았을 때(창을 닫음) 카카오페이 쪽 상태 | 주문 조회가 `AUTH_PASSWORD` 등으로 남는다 - 서버 승인이 없으면 돈이 안 움직인다고 읽었다 | 인증만 하고 창을 닫은 뒤 `payment_action_details` 를 본다 |
| 주문 조회 응답의 `cancel_available_amount` 가 승인 직후 0 으로 오는 문서 예시 | 우리는 그 칸을 조회에서 쓰지 않고 `amount.total` 에서 `CANCEL` 줄 합을 뺀다 | 승인 직후 조회 |
| 취소 응답이 일부 취소 뒤 `cancel_available_amount.total` 에 남은 금액을 준다 | 장부의 환불 뒤 남은 금액 판단이 틀어진다 - 환불이 「모름」으로 읽혀 조회로 맞춘다 | 부분 환불 두 번 |
| 취소 호출을 다시 보내면(응답 유실) 카카오페이가 중복을 막는다 | 이중 환불 - 그래서 장부는 환불 한 건에 호출을 한 번만 하고 모름이면 조회로 가린다(`cancel_available_amount` 로 막는 길은 일부러 쓰지 않았다) | 같은 취소를 두 번 보낸다(테스트 키) |
| 같은 `tid` 로 `approve` 를 다시 보내면 오는 오류 | 지금은 -780 으로 오고 모름으로 읽혀 조회가 `SUCCESS_PAYMENT` 를 확인한다 | 승인한 `tid` 로 한 번 더 |
| `ready` 마다 새 `tid` 가 만들어지고 이전 것은 결제되지 않은 채 남는다 | 주문에는 **마지막으로 저장한 `tid` 만** 박히고 승인은 그 번호로만 열린다 - 먼저 연 창(옛 `tid`)으로 결제하면 승인이 `409 ORDER_NOT_PAYABLE` 로 막힌다. 구매자는 가장 나중에 받은 창으로 결제해야 한다 | 같은 키로 주문을 두 번 만든다 |
| 운영 Secret key · 가맹점 코드(CID) · `cid_secret` 의 쓰임 | - | 제휴 뒤 |
<!-- payment-kakaopay:end -->
