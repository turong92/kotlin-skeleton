# 계정 수명주기 (account · auth-session · auth-magic-link)

> 목표: 도메인과 메일 설정만 바꿔 **켜기만 해도 가입 · 로그인 · 비밀번호 찾기 · 세션 관리 · 탈퇴가 있는 서비스 구색**이 나온다.
> 조립식: 모듈마다 캡슐화되어 있고 한 줄 의존성으로 붙는다. 사용 방식을 정하는 값은 모듈이 정하지 않고 앱 yml 이 명시한다.

## 모듈 지도와 경계의 이유

| 모듈 | 하는 일 | 왜 따로 |
|---|---|---|
| `auth` (확장) | 로그인 · JWT. 새로 생긴 것: `LoginHooks`(시도 앞뒤 고리) · `LoginSessionIssuer`/`SessionRevoker`(세션 SPI) · `AuthAccount.loginBlock`(막힌 계정) · `sid` 클레임 · 계정 없을 때도 해시 한 번(타이밍) · `upgradePasswordHash`(로그인 때 해시 올리기) | `auth` 만 쓰는 앱이 그대로 동작해야 한다 — 확장은 전부 선택 고리다 |
| `auth-session` (+`-jdbc`) | 리프레시 토큰 회전 · 재사용 탐지 · 세션 목록 · 철회 · 쿠키/바디 전달 | **계정 없이도** 쓸 수 있다 (`auth` 에만 의존). 자기 계정 저장소를 가진 앱도 세션만 얹을 수 있다 |
| `account` (+`-jdbc`) | 계정 · 로그인 수단 · 가입 · 확인 · 재설정 · 변경 · 삭제 · 운영자 · 한도 · 이벤트 · HTTP | 계정 저장소 포트(`AccountRepository`)와 메일 · 캡차 · 알림 고리를 가진 한 덩어리. 저장은 어댑터 |
| `auth-magic-link` | 메일 링크 로그인 | 같은 계정 위의 **수단 하나** — `SignInMethod` 가 새 수단이 계정 모듈을 건드리지 않고 붙는다는 증거 |
| `auth-social` (확장) | 제공자가 확인한 이메일인지(`emailVerified`)를 프로필에 싣는다 | 계정 연결 · 병합 규칙이 이 값을 믿는다 |
| `platform` (확장) | `AccountErasureListener` · `ErasureRequest` · `AccountTombstone` · `AccountDataExporter` | board · notification-jdbc 가 `account` 를 몰라도 삭제 고리를 구현하도록 공용 계약으로 |

의존 방향: `account → auth, platform` · `auth-magic-link → account` · `*-jdbc → 자기 계약 모듈 + persistence-jdbc`. 선택 통합(`notification-mail` · `captcha-turnstile` · `alert` · `idempotency` · `auth-social` · `job-queue-jdbc`)은 `account` 의 `compileOnly` 이고 각자 `@ConditionalOnClass` 자동설정이 있다 (`noOptionalTest` 가 없는 클래스패스를 증명).

## 한 줄로 붙이기

```kotlin
implementation(project(":modules:account-jdbc"))         // 계정 (+ account, auth)
implementation(project(":modules:auth-session-jdbc"))    // 리프레시 토큰 · 세션 (+ auth-session)
implementation(project(":modules:notification-mail"))    // 메일 (없으면 로컬은 로그, stage · prod 는 기동 실패)
implementation(project(":modules:auth-magic-link"))      // (선택) 메일 링크 로그인
```

```yaml
skeleton:
  account:
    mail:
      link-base-url: https://app.example.com      # 프론트 주소 — 메일 링크가 여는 곳
  notification-mail:
    enabled: true
    from: "My App <no-reply@example.com>"
```

환경변수는 relaxed binding 으로 그대로 된다 (`SKELETON_ACCOUNT_MAIL_LINK_BASE_URL` …). 비밀: `JWT_SECRET`(플랫폼이 만든다), `SPRING_MAIL_*`(SMTP). 첫 관리자: `skeleton.account.bootstrap.admin-email`.

## 흐름

**이미 계정이 있는 주소로 가입 요청**: 화면 응답은 새 주소와 같은 `202`(+ 같은 모양의 `signUpId` · `expiresAt` · `resendAvailableAt`). 메일은 코드 대신 "이미 계정이 있어요" — **로그인 페이지 링크**(`mail.link-base-url` + `mail.login-path`) · **가입 수단 문장**("구글로 가입되어 있어요.") · **비밀번호 재설정 링크**(`forgot` 와 같은 토큰 · 같은 주소별 재설정 한도 · 같은 상태 규칙, 비밀번호가 없는 계정은 이 링크로 첫 비밀번호를 정한다) · `auth-magic-link` 가 있으면 **1회용 로그인 링크**(`MagicLinkIssuer` — 링크 요청과 같은 한도; account 는 그 모듈에 의존하지 않는다). 정지된 계정에는 링크 줄이 없고, 한도가 찬 줄은 빠진다. 메일 한 통 자체가 주소별 메일 예산(3/시간)을 쓴다.
**가입 → 코드 확인(= 로그인)**: `POST sign-up`(늘 202 + 불투명한 `signUpId`) → 메일의 6자리 코드 → 프론트가 `POST verify-email {signUpId, code}` → **그 응답이 곧 로그인**(토큰). 가입은 계정이 아니라 **가입 시도**를 만든다 — 시도마다 자기 비밀번호 해시 · 자기 코드가 있고, 확인되기 전에는 계정의 어떤 자격도 저장되지 않는다. 틀린 코드는 `400 ACCOUNT.CODE_INVALID`(`data.attemptsLeft`), 만료 · 소진 · 이미 쓴 시도 · 그 사이 주소를 가져간 계정은 `410 ACCOUNT.CODE_EXPIRED`, 재전송은 `POST verification/resend {signUpId}`.
**조용한 갱신**: API 가 401 → (단일 비행) `POST refresh` → 새 쌍 → 한 번 재시도. `AUTH.REFRESH_*` 면 로그아웃, `429 AUTH.TOO_MANY_REFRESHES` 는 로그아웃이 아니라 `Retry-After` 뒤 재시도.
**비밀번호 찾기**: `POST forgot`(늘 202) → 메일 링크 → `POST reset`(모든 세션 종료, 이메일도 확인된 것으로 — 아래 "메일함 증명").
**이메일 변경**: `POST email/change`(다시 인증) → **새 주소로 6자리 코드**(옛 주소에는 알림) → **같은 세션에서** `POST email/change/confirm {code}` → 바뀜(다른 세션은 닫힌다).
**매직 링크**: `POST magic-link/request`(늘 202) → 메일 링크 → `POST magic-link/redeem` → 토큰.
**소셜 연결 · 해제**: 로그인한 채 `POST identities/social/{provider}` · `DELETE identities/{id}` — 둘 다 다시 인증이 필요하다. 마지막 수단은 못 뗀다.
**삭제**: 다시 인증(비밀번호 · 메일 코드 · 이메일 없는 계정은 소셜 코드) → `202 {purgeAfter}` → 즉시 로그인 불가 · 세션 종료 → 유예(30일) 뒤 `AccountErasureListener` 들이 지우고 **개인정보를 지운다 — 계정 행은 `ERASED` 로 남는다**(`deletion.mode`, 아래 "삭제 수명주기"). 정지된 계정은 삭제를 요청할 수 없다. 유예 중 로그인에 성공하면(`deletion.self-restore`) 탈퇴를 직접 취소할 수 있다.

### 코드 vs 링크 — 어디에 무엇을 쓰고 왜

| 흐름 | 방식 | 이유 |
|---|---|---|
| 가입 확인 | **코드** (가입 시도에 묶임) | 가입을 시작한 **브라우저**만 끝낼 수 있다 — 남이 시작한 시도는 주인이 끝낼 수 없다(시도 id 를 모른다) |
| 이메일 변경(새 주소) | **코드** (계정 · **세션**에 묶임) | 로그인한 세션 안에서 입력 — 공개 확인 링크가 없어지고 "다른 사람이 열어 버린 오래된 링크" 류가 사라진다 |
| 다시 인증(비밀번호 없는 계정) · 삭제 확인 | **코드** (계정 · 세션에 묶임) | 같은 이유. 메일을 받은 기기가 아니라 **요청한 세션**이 증명한다 |
| 비밀번호 재설정 | **링크** | 세션이 없다 — 주인은 다른 기기 · 로그아웃 상태에서 메일을 연다 |
| 매직 링크 로그인 | **링크** | 세션이 없다 (로그인 자체가 목적) |

링크를 **열기만** 해서는 아무것도 바뀌지 않는다 — 프론트가 라우트에서 POST 한다 (메일 보안 검사기가 GET 을 미리 열어도 링크가 안 타게). 코드 메일에는 "요청하지 않았다면 무시하세요 · 이 코드를 누구에게도 알려주지 마세요" 가 들어 있다.

### 코드 무차별 대입 계산

6자리(100만 가지) · 한 코드(챌린지)당 추측 **5번**(저장소가 `attempts_left` 를 조건부 UPDATE 한 문장으로 깎는다 — 동시 64개 추측도 5번만 평가된다) · 유효 **10분**(6자리 코드는 가입 · 이메일 변경 · 다시 인증 · 삭제 확인 모두 같다 — 링크 토큰인 재설정만 30분) · 재전송은 새 코드 + 시도 5번 초기화 · 시도당 최대 재전송 3번 · 재전송 간격 30초.
**만료된 가입 시도의 다시 받기**도 같은 재전송이다(`ChallengeStore.replaceCode` 는 만료된 줄도 되살린다 — 행이 청소 전이면): 시도 id 는 그대로(가입을 시작한 브라우저가 든 열쇠이고 되살려도 새 능력이 생기지 않는다), 재전송 횟수 3 · 쿨다운 30초 · **주소별 메일 예산 3/시간**(`verification:email`) · **주소별 추측 천장 40/시간**이 전부 그대로 걸린다 — 되살린 코드도 메일 예산을 쓰는 코드 하나일 뿐이라 아래 "≤ 5개 × 5번 + 3통 × 5번" 계산이 한 글자도 안 바뀐다. 행이 청소된 뒤(`cleanup.expired-retention`, 기본 1일)에는 조용히 — 새로 가입한다.
한 **주소**에 걸린 추측의 총량 — **계정을 몇 개 만들든, 가입 시도든 이메일 변경 챌린지든 같은 한 셈**이다. 주소별 버킷 셋을 가입과 이메일 변경(대상 주소)이 **함께** 쓴다: ① 열 수 있는 챌린지(`signup:email`, `verification.sign-up-attempts-per-email`=5/시간 — 넘으면 응답은 같고 가입 시도는 저장하지 않고, 이메일 변경은 **어떤 코드로도 이길 수 없는 줄**을 저장해 보이는 상태 · 남은 시도 카운트다운이 같다) ② 코드가 실제로 나가는 메일(`verification:email`, `per-email`=3/시간) ③ **추측 자체**(`guess:email`, `verification.guesses-per-email`=**40**/시간 — 가입 코드 입력과 이메일 변경 코드 입력이 같은 카운터, 시도를 깎기 **전에** 센다, 넘으면 429).
- **수치 상한**: 구조상 ≤ 5개 × 5번 + 3통 × 5번 = **시간당 40번**이고, ③ 카운터가 그 40 을 직접 강제한다 → 한 주소의 코드를 맞출 확률 ≤ 40 / 1,000,000 = **0.004% / 시간**. 지속 공격에서 첫 적중까지 중앙값 ≈ ln2 / 4·10⁻⁵ ≈ 17,300 시간 ≈ **2.0 년**, 기대값 ≈ 25,000 시간 ≈ 2.9 년 (고정 창이라 창 경계에서 한 시간 안에 최대 2 배가 몰릴 수 있으나 장기 평균은 40/시간 그대로). 계정 K 개를 만들어도 한 주소에 대한 총량은 그대로다 (이전에는 계정마다 25회/시간 → K=1000 이면 중앙값 약 28 시간).
- **주소 목록을 도는 공격**: 주소마다 40/시간이 아니라 **IP(IPv6 는 /64)마다** 코드 입력 30번/시간(`attempts-per-ip`, 가입 코드 입력 · 이메일 변경 코드 입력이 같은 버킷), 가입 요청 10건/시간(`sign-up.per-ip`) → /64 하나당 ≤ 30 추측/시간. S 개의 /64 로 ≤ 30·S 추측/시간 (적중 기대값 ≤ 3·10⁻⁵·S / 시간).
- **이 상한이 이메일 변경에서 의미하는 것**: 공격자 계정이 남의(미가입) 주소를 자기 계정 이메일로 **확인된 채** 가져가려면 위 확률을 이겨야 한다 → 사실상 불가능(위 수치)하므로 "나중에 그 주소의 진짜 주인이 나타나는" 상태에 닿지 못한다. 그래도 닿았다면(예: 도메인 소유 이전, 우연한 적중) 받아들이는 결과: 이메일 변경을 마친 계정은 **그 사람의 계정**이고 그가 미리 연결해 둔 로그인 수단도 그의 것으로 남는다 — 진짜 주인은 그 주소로 **가입할 수 없다**(이미 가입된 주소 안내 메일이 간다). 주인이 잠기거나 계정을 몰래 공유하지는 않는다: 주소는 한 계정에만 속하고, 주인이 비밀번호 재설정으로 그 계정에 들어가면 그 계정의 기존 수단도 그대로 보이므로 그 시점에 설정에서 정리한다(확인된 계정의 수단은 증명으로 지우지 않는다 — 지우면 정상 사용자의 소셜 연결이 사라진다).
- **첫 관리자 부트스트랩은 이메일 변경에서 일어나지 않는다**: 가입 코드 확인 · 비밀번호 재설정 · 매직 링크 · 확인된 소셜 로그인으로 메일함을 증명한 계정에만 (또는 관리자의 명시적 역할 부여). 이메일을 부트스트랩 주소로 바꾼 계정은 ADMIN 이 되지 않는다 — 주소를 가져간 것과 그 주소의 주인임을 증명한 것은 다르게 다루기 위해서다.
- 코드가 **메일 제목에는 없다**: 제목은 `LogOnlyMailTransport` · 발송 실패 때의 `SmtpMailSender` 가 로그에 남기므로 코드 · 링크는 본문에만 둔다.
코드는 암호학적 난수(`SecureRandom.nextInt(10^6)`, 균등 — 앞자리 0 도 쓴다)이고 저장소에는 **HMAC-SHA256(서버 비밀, 챌린지 id · 코드)** 만 간다: 6자리는 공간이 100만이라 **느린 해시로도 DB 유출에서 못 지킨다**(100만 번이면 끝난다) — 지키는 것은 서버 비밀(JWT 비밀에서 용도 접두사로 파생)이고 비교는 상수 시간이다.
알려진 대가 둘 (캡차 `captcha.required` 를 권고 — 둘 다 값싼 요청으로 남의 주소를 겨냥하는 것이라 사람 확인이 가장 직접적이다):
- **메일**: 한 주소의 메일 예산(3/시간)이 차면 제3자가 그 주소의 코드 · "이미 계정이 있어요" 메일을 시간당 몇 통 막을 수 있다 (이메일 변경 요청도 같은 예산을 쓴다).
- **가입 차단**: 한 주소의 열 수 있는 챌린지(5/시간)가 차면 제3자가 요청 5건/시간으로 **그 주소의 가입 시도 저장을 막을 수 있다** (응답은 같아 막힌 줄 모른다 — 주인은 코드 메일이 안 와서 알게 된다. 예산이 지나면 풀린다). 이메일 변경 요청도 같은 버킷을 쓰므로 계정 몇 개로도 같은 효과다. 잠기는 것은 가입뿐 — 기존 계정의 로그인 · 재설정은 영향이 없다.
- **세션 코드**: 계정+용도당 열린 코드는 하나라서 같은 계정의 다른 세션(훔친 토큰)의 새 요청이 주인의 열린 코드를 **교체**할 수 있다 — 계정당 5/시간으로 제한되는 성가심일 뿐 진입로가 아니다(주인이 다시 요청하면 된다). 다른 세션의 **입력**은 시도를 깎기 전에 거절되어 주인의 5번을 소모하지 못한다.

### 메일함 증명 — 한 번의 저장소 연산

이메일 확인 없는 가입(`sign-up.email-verification=false`)은 남이 피해자 주소로 ACTIVE · 미확인 계정을 만들 수 있다. 그 계정의 주인이 메일함을 **증명**하면(매직 링크 · 병합된 소셜 · 비밀번호 재설정 · 코드 가입) `AccountRepository.proveMailbox` 한 트랜잭션(계정 행 락)이: 증명한 수단 말고 **모든 로그인 수단을 지우고**(새 비밀번호를 정할 때는 옛 비밀번호 행도 지워 id 가 바뀐다 — 그 id 를 들고 있던 남의 뒤늦은 변경이 새 비밀번호를 덮지 못한다), 새 비밀번호를 쓰고, 확인 처리한다. 이어서 열린 코드 · 링크(이메일 변경 · 삭제 · 다시 인증 · 매직 링크 · 같은 주소의 가입 시도)와 세션을 닫는다. 이미 확인된 계정에서는 아무것도 지우지 않는다. 이 모드를 매직 링크 · 소셜 병합과 함께 쓰면 `AccountDeployGuard` 가 **경고**한다(문제가 아니라 경고인 이유: 증명이 심어 둔 것을 지우므로 남는 것은 "증명이 올 때까지 남이 ACTIVE 계정을 쥐고 있다" 와 409 로 존재가 드러나는 것뿐이다).

## 새 로그인 수단 더하기 (정확한 순서)

1. **`SignInMethod` 빈 하나**: `code`(소문자 · 숫자 · `_`, 저장되는 값) · `normalize`(주체 정규화) · `provesEmail`(증명이 곧 메일함 소유면 true) · `exposesSubject` · `userRemovable` · `countsAsCredential`.
2. **자기 엔드포인트**에서 증명을 끝낸다 (비밀번호 확인 · OAuth 코드 교환 · 링크 소비 · 패스키 서명 …).
3. 증명이 끝나면 `AccountSignInService.signIn(SignInProof(method, subject, email, emailVerified, …, allowSignUp))` → `AuthAccount?` 를 받아 `AuthTokenResponseFactory.issue(it)` 로 토큰(막힌 계정 거르기 · 세션 열기 포함)을 낸다.
4. 스키마 변경 없음 — `identities.method` 는 문자열이다. 충돌 규칙(아래)과 마지막 수단 보호는 자동으로 따른다.

**새 OAuth 제공자**는 위 1~3 이 필요 없다: `auth-social-xxx` 모듈이 `OAuthProvider` 빈만 내면 계정 모듈의 `SignInMethodSource` 가 그 제공자 id 로 수단을 만들고, 로그인 · 연결 엔드포인트가 그대로 동작한다. 제공자가 이메일 확인 여부를 알면 `OAuthUserProfile.emailVerified` 에 싣는다 (모르면 false — 안전한 쪽).
**새 소셜 말고 수단**(예: 매직 링크)의 모범은 `modules/auth-magic-link` (코드 약 150줄).

## 충돌 · 병합 규칙

| 상황 | 결과 |
|---|---|
| 같은 `(method, subject)` 가 이미 있다 | 그 계정으로 로그인 |
| 소셜 · 제공자가 **확인한** 이메일이 기존 계정과 같다 | 모듈 기본: **병합 없이** `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT` — 기존 방법으로 로그인해 설정에서 연결한다. `social.merge-on-verified-email=true`(`apps/api` · `apps/sample` 이 명시적으로 켠다)면 제공자가 확인한 이메일(Kakao: 확인 **+ 유효**, Naver · **LINE · X**: 확인 정보가 없어(또는 문서가 보증하지 않아) 병합 안 됨 — `auth-social-oidc` 의 `email-trust` · `auth-social-x` 의 `trust-confirmed-email` 이 기본 미확인)이 **글자 그대로** 같을 때 그 계정에 붙고(프론트는 409 가 아니라 200 로그인을 본다) 계정 주소에 알림 메일 + `IDENTITY_LINKED` 이벤트. 기존 계정이 **미확인**이면 제공자의 확인이 메일함 증명이라 남이 심은 자격을 모두 버린다(위 "메일함 증명"). **정확한 규칙(주인용)**: 기본 모드에서는 미확인 계정이 **존재하지 않는다**(가입 시도는 계정이 아니다) — 병합은 늘 **확인된 계정**에만 붙고, 미확인 계정에 붙는 일은 `email-verification=false` 모드에서만 있으며 그때도 안전하다(증명이 남의 자격을 지운다). **남는 위험**: 제공자의 주소가 다른 사람에게 재배정되는 경우 — 비밀번호 재설정과 같은 신뢰 가정이다 |
| 소셜 · 제공자가 확인하지 **않은** 이메일 | 이메일을 없는 것으로 친다 — 저장하지 않고(남의 주소 선점 방지) 충돌도 알리지 않는다(주소 존재 조회 방지) |
| 매직 링크(메일함 증명) · 같은 이메일의 기존 계정 | 그 계정에 붙는다(`sign-up=false` 여도 — 가입이 아니다) + 이메일 확인으로 친다. 계정이 **미확인**이었다면(확인 없는 가입 모드) 남이 심은 모든 수단 · 코드 · 세션을 **버린다** (사전 탈취 방어) |
| 마지막 "들어오는 길" 을 떼려 한다 | `409 ACCOUNT.LAST_SIGN_IN_METHOD` (원자적 — 동시 해제 둘이 다 성공하지 않는다) |

## 이메일 정규화 (한 곳)

`Emails.normalize` = 앞뒤 공백 제거 → 유니코드 NFC → 소문자(`Locale.ROOT`). 점 · `+태그` 제거나 NFKC · IDN 변환은 **하지 않는다** (같게 보면 남의 주소를 같다고 말하게 된다). 저장 · 조회 · 한도 키 · 토큰 주인 · 메일 수신자가 모두 이 값이고, 같은 주소인지는 DB 정렬에 맡기지 않고 `AccountCore.accountByEmail` 이 **저장된 글자와 글자 그대로** 비교한다. MySQL 은 `email` · 토큰 `subject` 열도 `utf8mb4_bin` 이다 (기본 정렬은 악센트 · 대소문자를 같게 본다). 메일은 늘 계정에 **저장된** 주소로 간다.

## 위협 모델 — 각 항목을 덮는 시험

| 위협 | 막는 법 | 덮는 시험 |
|---|---|---|
| **계정 열거 (응답)** | 가입 · 재설정 · 재전송 · 매직 링크 요청은 계정 유무와 무관하게 같은 `202` 본문(가입은 `status` + 같은 모양의 `signUpId`). 이미 확인된 주소의 시도에는 코드 대신 "이미 계정이 있어요" 메일이 가고 코드는 누구도 모르므로, 코드 입력은 새 주소의 틀린 추측과 똑같이 줄어들다 끝난다 | `AccountWebTest` `sign-up answers 202 with an opaque attempt id identically…` · `forgot is 202 and identical…`, `SignUpVerificationTest` `an already registered address looks the same…`, `MagicLinkWebTest` `request is 202 with the same body…` |
| **계정 열거 (시간)** | 요청 스레드는 검사 · 한도 · 해시 한 번 · 시도 저장 한 번을 새 주소든 · 진행 중이든 · 있는 주소든 똑같이 하고(**계정 저장소는 읽지 않는다**) 코드 · 메일은 뒤에서. 로그인은 계정이 없어도 해시 비교 한 번 | `SignUpVerificationTest` `enumeration parity - the request thread does the same work…`, `AccountResponseLevelSecurityTest` `sign-up, forgot and resend answer without waiting for mail…`, `PasswordServiceTest` `forgot never touches the stores on the request thread…`, `PasswordLoginServiceTest` `unknown account still costs one hash comparison…` |
| **열거 (잠금 오라클)** | 로그인 한도는 입력 식별자를 계정이 있든 없든 똑같이 센다 | `LoginControlsTest` `the eleventh attempt on one identifier… whether or not the account exists` |
| **무차별 대입** | IP 당 · 주소당(`email` · `username` · `accountId` 한 버킷) 시도 수 제한(429 + `Retry-After`), 현재 비밀번호 추측도 계정당 제한, 경보. **IP 출처**: `skeleton.web.client-ip.mode` 를 정해야 한다 — 안 정하면 `X-Forwarded-For` 로 한도가 풀리므로 stage · prod 가드가 기동을 막는다 | `LoginControlsTest`, `EmailNormalizationTest` `the login limit key is one per address…`, `AccountDeployGuardTest` `without a client IP mode…`, `AccountWebTest` `login attempts are throttled…`, `PasswordServiceTest` `guessing the current password…` |
| **메일 폭탄** | 주소당 · IP 당 한도 (넘으면 조용히/429). 알려진 대가: 한 주소의 시간당 예산(3)이 차면 제3자가 그 주소의 인증 · 재설정 · 매직 링크를 막을 수 있다 — 캡차(`captcha.required`)를 권고 | `RegistrationServiceTest` `resend is capped per IP like forgot…`, `SignUpVerificationTest` `mails to one address are budgeted but the attempt is still stored and answered the same` · `at most three resends per attempt` · `a new code for the same attempt - needs the cooldown, is capped…`, `EmailChangeAddressBudgetTest` `many accounts asking to change to one free address cannot mail it more often than the per-address mail budget…`, `PasswordServiceTest` `forgot is capped…`, `MagicLinkWebTest` `requests are capped…` |
| **링크 토큰 탈취 · 추측** | (재설정 · 매직 링크) 256비트 난수, **해시만 저장**, 한 번만, 용도 구분(매직 링크로 재설정 불가), 만료, 새 링크가 옛 링크를 닫음 | `OneTimeTokensTest`, `JdbcTokenAndAuditDbTest`, `PasswordServiceTest` `a magic link cannot reset a password`, `MagicLinkWebTest` `a link for another purpose…` |
| **링크 · 코드 이중 사용 (경쟁)** | 링크 소비 · 코드 챌린지 삭제는 원자적 조건부 문장 — 같은 코드로 겹친 두 요청은 하나만 이긴다 | `OneTimeTokensTest` `sixteen threads…`, `PasswordServiceTest` `sixteen concurrent resets…`, `MagicLinkWebTest` `sixteen simultaneous redemptions…`, `SignUpVerificationTest` `two attempts for one address that both hold a right code - exactly one wins…`, `ChallengesTest` `concurrent guesses can never spend more than the allowed attempts` (+ 두 DB `JdbcChallengeStoreDbTest` `sixty-four concurrent wrong guesses…` · `consuming a challenge is won by exactly one…`) |
| **링크 미리 열기(보안 검사기)** | 열기는 GET, 바꾸는 것은 프론트의 POST (재설정 · 매직 링크). 가입 · 이메일 변경 · 삭제는 클릭할 링크가 없다 | 설계 (정책 실패가 링크를 태우지 않는 시험: `PasswordServiceTest` `a policy failure leaves the link usable`) |
| **리프레시 토큰 탈취 · 재사용** | 불투명 · 해시 저장 · **매번 회전** · 쓴 토큰은 **세션이 끝날 때까지** 기억 · 다시 오면 그 세션 종료 + `REFRESH_REUSE_DETECTED` 이벤트(WARN 로그 · 감사 · 주인 경보) | `SessionServiceTest` `replaying a rotated-away token…`, `SessionReuseMemoryTest`, `SessionBodyDeliveryWebTest` `reuse reaches the listeners…`, `SessionEventBridgeTest` (세션 → 계정 이벤트 → 경보), apps/api `AccountJourneyIntegrationTest` `a replayed refresh token reaches the account event stream…`, `sixteen threads presenting the same token…`, `SessionBodyDeliveryWebTest` `refresh is public, rotates…`, `JdbcSessionStoreDbTest` (두 DB) |
| **세션 고정** | 세션 id · 리프레시 토큰은 항상 서버가 새로 만든다(클라이언트가 정하는 값 없음), 로그인마다 새 세션. 비밀번호 재설정 · 변경 · 이메일 변경(확인한 세션만 남는다) · 수단 해제는 세션을 닫는다 | apps/api `AccountJourneyIntegrationTest` `a password reset kills every refresh token for real…` · `a password change signs the other devices out…` · `an email change confirmation signs the OTHER sessions out…` (진짜 `SessionRevokerAdapter` + JDBC 저장소), `SessionBodyDeliveryWebTest` `login carries a refresh token, a session id…`, `PasswordServiceTest` `reset replaces the password, signs every session out…`, `EmailChangeServiceTest` `entering the code in the same session switches the address…` |
| **CSRF** | 기본 body 전달은 쿠키가 없다. cookie 모드는 HttpOnly · Secure · SameSite=Strict · `Path=/api/v1/auth` + 커스텀 헤더 요구. 액세스 토큰은 `Authorization` 헤더 | `SessionCookieDeliveryWebTest` (헤더 없으면 403, body 토큰 무시, 쿠키 속성) |
| **정지 · 삭제 뒤 토큰** | 새로고침 즉시 거부, 세션 즉시 철회. **이미 낸 액세스 토큰은 만료(≤15분)까지 산다** — 알려진 한계 | `SessionBodyDeliveryWebTest` `a suspended or deleted account cannot refresh`, `DeletionAndAdminTest` |
| **이메일 변경 탈취** | 새 주소 확인 전엔 불변 · 다시 인증(비밀번호 · 메일 코드 · 이메일 없는 계정은 소셜 코드) · 새 주소로 간 코드는 **요청한 세션에서만**(다른 세션의 입력은 시도를 깎기 전에 `CODE_EXPIRED`. 세션 묶음이 막는 것은 "코드를 받은 편지함의 주인이 아닌 다른 세션" 이고, **훔친 액세스 토큰이 같은 `sid` 를 싣고 있는 한** 그 세션 자체는 못 막는다 — 그것을 막는 것은 다시 인증 + 새 주소의 메일함이다) · 시도 5번 · 옛 주소에 요청 · 변경 알림(새 주소가 쓰이는 중이어도 똑같이) · **비밀번호를 바꾸면 열린 코드가 죽는다** · 변경 시 다른 세션 종료 · 열린 다시 인증 · 삭제 코드와 옛 주소의 링크도 닫힌다 | `EmailChangeServiceTest` (`the code works only in the session that asked…` · `a wrong code counts down…` · `confirming a change also closes…`), `ReauthTest`, `AccountWebTest` `an email change is confirmed with the code…`, `ChallengesTest` `another session's guesses are refused BEFORE an attempt is spent…`, `EmailChangeServiceTest` `a thief session hammering the confirm endpoint…` · `decision - one open code per account and purpose…` |
| **이메일 변경으로 주소당 추측 상한 우회 (물량)** | 이메일 변경 챌린지도 가입과 **같은 주소별 버킷**(열기 5 · 메일 3 · 추측 40 / 시간)을 쓴다 — 계정을 몇 개 만들든 한 메일함의 총량이 같다. 예산을 넘은 요청은 같은 응답 · 같은 보이는 상태이고 그 챌린지는 어떤 코드로도 이길 수 없다. 코드 입력은 가입 코드 입력과 **같은 IP 버킷**. 이메일 변경은 첫 관리자 부트스트랩을 일으키지 않는다 | `EmailChangeAddressBudgetTest` (`many accounts asking…` · `sign-up attempts and email-change challenges draw on ONE per-address open budget` ×2 · `over budget the visible state and the countdown are identical…` · `an over-budget challenge can never be won…` · `an email change never fires the first-admin bootstrap` · `confirming is limited per IP, in the same bucket as sign-up code entry` · `the per-address guess cap counts sign-up guesses and email-change guesses together`) |
| **재전송으로 가입 여부 열거** | 확인된 계정이 있는 주소의 가입 시도도 없는 주소와 **똑같이 재발급**한다(남은 추측 · 만료 · 쿨다운 · 재전송 횟수가 같다) — 다른 것은 메일 종류뿐 | `ResendOracleTest` `the four-request probe answers identically…` |
| **재사용 탐지 선점 (회전 한도로 덮기)** | 이미 쓴 토큰은 회전 한도를 보지 않고 바로 재사용 규칙으로 간다 — 도둑이 창을 채워도 주인의 옛 토큰은 `REFRESH_REUSED` + 세션 폐기 | `SessionServiceTest` `a thief who fills the rotation window cannot shield the session…` |
| **코드가 메일 제목 · 로그에** | 제목에 코드 · 링크 없음(ko · en 전 템플릿), 모든 코드 · 링크 메일 종류를 실제 메일러 길(`TemplatedAccountMailer` + `LogOnlyMailTransport` / 실패하는 `SmtpMailSender`)로 보내 로그에 없음을 확인 | `MailPathLogLeakTest` (`no mail kind that carries a code or a link leaves it in a log line…` ×2 · `no template, ko or en, puts a code or a link in the SUBJECT`) |
| **IPv6 주소 돌려쓰기로 IP 한도 우회** | 모든 IP 한도(가입 · 재전송 · 코드 입력 · 재설정 · 로그인 · 매직 링크 · 이메일 변경 코드 입력)는 `ClientIps….limitKey`(IPv6 /64). 감사 · 캡차는 전체 주소 | `ClientIpLimitKeyWebTest` ×4, `MagicLinkClientIpKeyTest`, (Redis · 멱등 키: `PrincipalAwareRateLimitKeyResolverTest`) |
| **메일함 증명 뒤에 늦게 닿는 쓰기** | 다시 인증이 본 `email_verified` 를 계정 행 락 안에서 다시 확인한다(소셜 연결 · 이메일 변경 입력) — 증명 전에 시작한 쓰기는 증명이 지운 것을 되살리지 못한다. 증명 트랜잭션이 그 계정의 열린 코드와 그 주소의 가입 시도도 같이 지운다. 이메일 변경 챌린지는 요청 때 본 확인 상태를 담아 입력 때 다르면 죽는다 | `LateLandingAfterMailboxProofTest` ×3, `JdbcMailboxProofDbTest` (`an identity insert that saw the account unverified is refused…` · `an email change that saw…` · `the proof transaction also closes…` · 두 경쟁 시험, 두 DB) |
| **확인된 계정을 옛 가입 시도가 덮음** | 맞는 코드여도 이미 확인된(또는 삭제 유예 중인) 계정이 있는 주소의 시도는 `CODE_EXPIRED` — 비밀번호 · 수단 불변, 로그인 없음 | `VerifiedAccountNeverOverwrittenTest` ×2 (각 조건을 따로 지운 변형에서 실패함을 확인) |
| **해시 업그레이드가 재설정을 되돌림** | 로그인이 검증한 해시가 아직 저장돼 있을 때만(`where id = :id and secret = :old`) 새 해시로 바꾼다 | `RegistrationServiceTest` `a hash upgrade that lost a race…`, `PasswordLoginServiceTest` `a successful login re-hashes…`, `JdbcMailboxProofDbTest` `a conditional secret update…` |
| **코드 · 키 설계** | 코드는 `nextInt(10^6)` 그대로(modulo 없음) · 어떤 JVM 로케일에서도 ASCII 6자리 · 해시 키는 `"account-code/" + jwt.secret` | `ChallengesTest` (`the code is exactly the generator's nextInt(1_000_000)…` · `statistical sanity…` · `…whatever the JVM's default locale…`), `AccountAutoConfigurationTest` `the code hash key is derived from the JWT secret…` |
| **제공자 인가 코드 재사용 (소셜 다시 인증)** | 제공자가 코드를 한 번만 받는다 — 같은 코드로 두 번째는 `REAUTH_FAILED`. 소유 검사(`findIdentity(provider, sub).accountId == accountId`)는 진짜 검증기로 시험한다 | `SocialReauthTest` `a provider authorization code is single use…` · `a code that proves a provider account of someone else does not count - the REAL ownership check runs` |
| **막힌 계정의 코드 가입이 성공 로그인으로 남음** | `LOGIN_SUCCESS` · `lastLoginAt` 은 토큰 발급이 성공한 뒤에 기록 | `VerifyEmailSignInRecordingWebTest` |
| **사전 탈취 (인증 단계)** | 가입 시도 = (시도 id · 이메일 · **그 시도의** 비밀번호 해시 · 코드 해시). 코드는 주소의 메일함으로만, 시도 id 는 브라우저만 안다 → 공격자의 시도는 주인이 끝낼 수 없고 공격자는 코드를 못 본다(추측 5번 · 주소당 상한). 주인 자신의 시도는 **주인의 비밀번호**로 끝나고 그때 같은 주소의 다른 시도는 모두 버려진다. 관리자 부트스트랩 주소도 같다 | `SignUpVerificationTest` `pre-hijack - the attacker's attempt cannot be finished by anyone but the attacker…` · `pre-hijack through resend…` · `pre-hijack of the bootstrap admin…` · `an attacker who never sees the code cannot finish their own attempt…` · `a code that is wrong or typed with the wrong attempt id never matches…` |
| **확인 없는 가입의 심어 둔 자격** | 메일함 증명(매직 링크 · 병합된 소셜 · 재설정 · 코드)은 한 번의 저장소 연산으로 증명한 수단 외 모든 수단을 지우고 열린 코드 · 링크 · 세션을 닫는다. 비밀번호 변경과의 경쟁은 옛 비밀번호 행 id 를 바꿔 이긴다 | `MailboxProofTest` (`a magic link proof removes every identity but the proving one…` · `a merged social proof…` · `a password reset keeps only the new password…` · `a password change that races a mailbox proof…` · `a squatter's password change that races the owner's password reset…`), `JdbcMailboxProofDbTest` (두 DB, 30회 경쟁), `AccountDeployGuardTest` `no email verification together with a mailbox-proving method…` |
| **이메일 없는 계정의 다시 인증 면제** | 주소가 없는 계정(Naver · LINE · X · 확인 안 된 이메일의 소셜 가입)은 이미 연결된 제공자의 **새** 인가 코드(`socialReauth`)로 이메일 변경 · 소셜 연결 · 해제 · 삭제를 한다 — 다른 계정의 제공자 계정 · 연결 안 된 제공자 · 거부된 코드는 `REAUTH_FAILED`. 삭제도 할 수 있다 | `SocialReauthTest`, `SocialLinkServiceTest` `a social re-authentication proves only a provider that is already linked to this very account`, `AccountSocialWebTest` `an account without an email…` ×2, `AccountSocialPkceWebTest` (검증기가 없으면 증명 실패가 아니라 400 PKCE), `GlobalSocialJourneyIntegrationTest` (LINE · X 로 가입 · 연결 · 해제 · 삭제, 진짜 PostgreSQL) |
| **로그인 수단 해제 탈취** | 해제도 다시 인증(`DELETE identities/{id}` + 본문) — 훔친 액세스 토큰만으로 주인의 비밀번호 · 소셜을 떼어 내지 못한다. 증명은 떼기 전에 태운다 | `SocialReauthTest` `unlinking a sign-in method needs the re-authentication of the account kind` · `unlinking asks a password account for its password…`, `AccountWebTest` `profile update validates and the last sign-in method cannot be removed` |
| **다시 인증의 단일 사용 · 순서** | 증명(코드)은 연결 **전에** 태우고(같은 코드로 겹친 두 연결은 하나만), 증명 검사는 제공자 코드 교환 **앞**이다(제공자 코드는 한 번만 쓸 수 있다 — 틀린 비밀번호가 그 코드를 태우지 않는다). 증명은 계정 · 세션에 묶이고 5번 추측 뒤 죽는다. 작업(액션)에는 묶이지 않는다 — 삭제 코드만 따로 | `SocialLinkServiceTest` `one mailed code authorizes one link…` · `the re-authentication proof is checked BEFORE the authorization code is exchanged…`, `ReauthTest` `a code is worth five guesses, then it is dead`, `DeletionAndAdminTest` `an account without a password confirms with a mailed six digit code entered in the same session` |
| **리프레시 토큰 행 무한 증가** | 세션 하나가 창 안에 회전할 수 있는 횟수를 묶는다(`auth-session.rotation`, 기본 10분에 30번, `RateLimitStore` — 넘으면 `429 AUTH.TOO_MANY_REFRESHES`, 세션은 그대로). 쓴 토큰을 세션 끝까지 기억하는 재사용 탐지는 그대로이므로 행 수 ≤ 30 × (세션 수명 ÷ 10분) | `SessionServiceTest` `a session can rotate only so often…` · `the rotation limit is per session…`, `RotationKeyWiringTest` (후속 사슬이 JWT 비밀에서 파생됨 — 파생 줄을 지우면 실패) |
| **멱등 키에 묶인 인증 실패** | 비밀번호 · 코드를 싣는 멱등 명령(`email/change` · `delete`)은 4xx 를 키에 묶지 않는다 (`cacheClientErrors=false` — 오타 한 번이 그 키를 죽이지 않는다). 성공은 그대로 재생 | `AccountWebTest` `an authentication failure is never cached under an Idempotency-Key…`, `InMemoryIdempotencyStoreTest` `release frees the key…` |
| **유사 주소 (악센트 · 대소문자)** | 정규화 한 곳 + 글자 그대로 비교 + MySQL `utf8mb4_bin` | `EmailNormalizationTest`, `EmailExactMatchDbTest` (두 DB) |
| **소셜 연결 탈취 (로그인 CSRF)** | 연결은 다시 인증(현재 비밀번호 · 메일 코드 · 소셜 코드) + 계정에 알림 메일 + 프론트가 OAuth `state` 를 확인해야 한다 (계약 문서 §7) | `AccountSocialWebTest` `linking needs the current password…` · `an account without a password links only after the mailed code…` |
| **소셜 병합 탈취** | 확인된 이메일만 믿고 기본은 병합하지 않는다 (위 표) | `SignInServiceTest` `a verified provider email that matches…` · `merging is opt-in…` · `a provider email that is not verified is ignored…`, `AccountSocialWebTest` |
| **비밀번호 · 토큰 · 코드가 로그에** | 서비스는 안 남기고, 요청/응답 DTO · 저장 행 `toString` 은 가린다(Spring MVC 가 DEBUG/TRACE 에서 본문 · 응답 객체를 `toString` 으로 찍는다 — 실측: 가입 id 가 응답 객체 로그로 샌 것을 잡았다). 기본 `LogMasker` 는 `token=` 쌍도 가린다 | `AccountResponseLevelSecurityTest` `no one-time token, code, sign-up id or password ever reaches the log…`, `MailPathLogLeakTest`, `SecretsStayOutOfToStringTest` |
| **권한 상승 · 관리자 사고** | 관리자 권한은 **매 호출 저장소의 계정**(ACTIVE + 역할)으로 확인 — 낡은 토큰으로 못 한다. 첫 관리자는 확인된 이메일 + ADMIN 이 아직 없을 때만, 마지막 ADMIN 은 정지 · 회수 · 삭제 불가(저장소가 원자적으로 판정 — 동시에 서로를 정지해도 0 이 안 된다), 자기 정지 불가, 비밀번호 기본값 없음, 시드 계정은 stage · prod 에서 기동 실패 | `RegistrationServiceTest` (bootstrap), `DeletionAndAdminTest` `two administrators suspending…`, `AccountCallersTest`, `JdbcAccountRepositoryDbTest` `two administrators demoting…` (두 DB), `AccountDeployGuardTest` |
| **로그인 잠금 DoS** | 시도 전부를 세므로 공격자가 남의 식별자를 10분 동안 잠글 수 있다 — **수용한 대가** (실패만 세려면 잠김 상태를 저장해야 하고 그것이 존재 오라클이 된다). 창 · 횟수는 설정 | `LoginControlsTest` |
| **삭제 뒤 남는 데이터** | 유예 뒤 모든 `AccountErasureListener` 가 성공해야 계정을 정리한다(하나라도 실패하면 다음 주기에 재시도, 한 계정의 실패가 같은 묶음의 다른 계정을 막지 않는다). 기본(`ANONYMIZE`)은 행을 `ERASED` 로 남기고 **개인정보 열 · 로그인 수단 · 역할 · 토큰 · 코드 · 감사 행의 IP · 상세**를 한 트랜잭션으로 지운다. 가입 시도 · 코드 행은 만료 뒤 같은 청소가 지운다 | `AccountErasureTest` (13+), `JdbcErasureDbTest` ×2 DB — 특히 `after erasure no column of any table still holds…` (모든 표 · 모든 열을 훑는 `PlantedDataScan`), `AccountJourneyIntegrationTest` (board · notification · sessions · consents 까지 모든 모듈 표), `DeletionAndAdminTest`, `AccountPurgeJobTest`, `NotificationInboxErasureDbTest`, `SessionMaintenanceTest`. **감사 표**(`audit.enabled` 일 때): 사건 줄(종류 · 시각 · 계정 id)은 남고 **그 계정 줄의 IP · 상세는 `ANONYMIZE` · `DELETE` · 운영자 지우기 모두에서 지운다**(`JdbcErasureDbTest` `the DELETE mode purge leaves the same nothing behind…` · `a forced purge…`). 계정 id 가 없는 줄(없는 계정에 대한 로그인 실패 …)은 이 사람의 것으로 가려낼 수 없어 **그대로 둔다**(`…cannot be attributed to the person and stay`) — 보존 기간은 앱이 정한다. `DELETE` 모드에서는 행이 없어 감사 줄에 계정 id 만 매달린 채 남는다 |
| **탈퇴 직후의 늦은 복구 · 지우기 경주** | 되살리기와 지우기는 둘 다 `status = 'DELETED'` 조건부이고 지우기는 계정 행 락 안에서 조건을 다시 본다 — 하나만 이긴다 | `JdbcErasureDbTest` `a restore racing the erasure at the end of the grace has exactly one winner` (두 DB) |
| **지워진 행에 다시 쓰기** | `ERASED` 행에는 저장소가 조건(`status <> 'ERASED'`)으로 아무것도 쓰지 않는다 — 지운 **뒤** 도착한 옛 액세스 토큰의 `PATCH /me`(이름 · 로케일 · 시간대), 지우기와 겹친 정지 해제, 이메일 변경, 로그인 수단 연결 모두. 서비스도 먼저 본다(`404`) | `ErasedRowStaysErasedTest` ×4, `AccountWebTest` `after an administrator erased the account, the owner's still-valid access token…`, `JdbcErasureDbTest` `nothing is written to an erased row…` (두 DB) |
| **지우기와 취소의 경계 경주 (리스너가 먼저 도는 문제)** | 지우기는 리스너(board 톰스톤 · 받은편지함 삭제)를 부르기 **전에** 계정 행을 **선점**한다(`claimErasure` — 계정 행 락 안에서 조건 확인 + `erase_claimed_at`). 선점된 계정은 되살리기 · 상태 변경이 거절된다 — 시계가 어긋난 취소가 이겨서 ACTIVE 인데 톰스톤이 찍힌 계정이 생기지 않는다. 리스너가 실패하면 선점은 남고 다음 주기에 다시 한다(유예는 이미 끝났다). 운영자 지우기도 같다(정지 해제가 거절됨, 실패는 `503 ACCOUNT.ERASURE_RETRY`) — 단 **첫 리스너가 성공하기 전에** 실패했으면 아무것도 지워지지 않았으니 선점을 되돌린다(`releaseErasureClaim` — 정지 해제가 그대로 된다). 일부가 성공한 뒤 실패했으면 선점을 유지한다: 정지 해제는 `409 ACCOUNT.ERASURE_IN_PROGRESS`(이벤트 없음)이고, **같은 지우기 호출을 다시 부르면 이어서 끝난다**(리스너는 멱등이라 처음부터 다시 돈다). 저장소가 거절한 정지 · 정지 해제는 204 · 이벤트를 내지 않는다 | `ErasureClaimTest` ×8, `JdbcErasureDbTest` `claiming the erasure…` · `an administrator's forced claim…` · `a claim racing a restore…` · `releasing a claim…` (두 DB) |
| **정지로 탈퇴해 빠져나가기** | 정지된 계정은 삭제를 요청할 수 없고(`ACCOUNT.SUSPENDED_CANNOT_DELETE`), 탈퇴 유예 중에 정지되면 유예가 끝나도 지워지지 않는다. 로그인 수단이 남아 같은 이메일 · 제공자 주체로 다시 가입할 수 없다. 운영자가 지울 때는 해시만 남는 재가입 차단이 생긴다 (아래) | `FrozenAccountTest` ×9, `SignInDepartedAndFrozenTest` (매직 링크 · 병합 소셜이 정지 계정에 수단을 붙이거나 로그인을 기록하지 않는다), `BlockedAddressBypassTest` (이메일 변경 확인 · 수단 연결로 차단 우회 불가), `JdbcAccountBlockDbTest` ×4 (두 DB), `AccountWebTest` `an admin erases a suspended account…` |
| **탈퇴 취소 토큰 오남용** | 취소 토큰은 로그인 **성공 뒤에만** 만들어지고(조회만으로는 만들지 않는다) 한 번 쓰고 · 15분 뒤 죽고 · 새로 만들면 이전 것이 죽고 · 다른 용도로는 못 쓰고 · 정지되면 못 쓴다. 주소당 시도 수를 센다 | `SelfRestoreTest` ×14, `SelfRestoreWebTest`, `MagicLinkSelfRestoreWebTest` (비밀번호로 가입해 매직 링크 수단이 없는 계정도 유예 중 매직 링크로 취소 · 유예가 끝난 계정에는 링크를 보내지 않는다) |
| **여러 인스턴스의 중복 정리** | 정리는 짧은 임대(`account_locks`)를 쥔 하나만 돈다. 해제는 **가져간 쪽의 소유 표가 맞을 때만** — 일 도중 만료돼 다른 인스턴스가 가져갔다면 늦게 끝난 옛 보유자의 해제가 그 임대를 풀지 못한다. 잡 큐가 있으면 주기마다 한 인스턴스만 잡을 넣는다 | `AccountErasureTest` `two overlapping purge runs…`, `AccountPurgeJobTest` `repeated ticks inside one interval…`, `AccountMaintenanceLeaseTest`, `JdbcAccountMaintenanceLeaseDbTest` (두 DB, 32 스레드 + 소유 표) |

## 닉네임 (`skeleton.account.display-name.*`)

게시판 · 댓글에 계정 id(`acc_…`) 대신 사람이 읽는 이름이 보이게 하는 부분이다. 모듈은 메커니즘만 내고, 어떤 방식을 쓸지는 앱의 `application.yml` 이 정한다 — 기본은 이 기능 이전과 같다(중복 허용 · 선택 · 자동 닉네임 없음).

| 키 | 기본 | 뜻 |
|---|---|---|
| `uniqueness` | `NONE` | `NONE` 중복 허용 · `UNIQUE` 비교용 키가 같은 닉네임은 하나만(`409 ACCOUNT.DISPLAY_NAME_TAKEN`) · `TAGGED` 중복 허용 + 서버가 4자리 꼬리표를 붙인다(`닉네임#0417`) |
| `required-on-sign-up` | `false` | `true` 면 이메일 가입 요청에 닉네임이 없을 때 `400` (필드 `displayName`, 코드 `Required`) |
| `fallback` | `NONE` | `GENERATED` 면 닉네임 없이 만들어지는 계정(소셜 · 매직 링크 · 이름 없는 가입)에 `user-1a2b3c` — 무작위 6자리 16진수. **이메일 앞부분은 쓰지 않는다**(개인정보) |
| `reserved` | `[]` | 거절할 닉네임(운영자 · 예약어 …). 대소문자 · 전각 · 띄어쓰기 · `_` 를 무시하고 비교한다. 운영자 역할 계정과 시드는 예외. 기본은 꺼짐 — 사칭 방지의 최소선이다 |

**규칙**(`DisplayNameRules` — 가입 · 프로필 수정 · 제공자 이름 · 시드가 모두 거친다): 앞뒤 공백 제거 · 연속 공백 한 칸 · 1..60 글자(코드 포인트) · 제어 문자 · 줄바꿈 · 보이지 않는 문자(zero-width · 방향 제어 · 한글 채움 문자 · 점자 빈칸 …) 거부 · `#` · `@`(전각 포함) 거부(꼬리표 · 멘션과 헷갈린다) · `deleted:` 접두 거부(탈퇴한 작성자 톰스톤과 같은 모양) · 보이는 글자가 하나도 없는 이름 거부. 사람이 낸 값은 **거절**(400 필드 오류)하고, 소셜 제공자 · 시드가 준 값은 **고쳐 쓴다**(가입을 막을 수 없다 — 글자를 떨구고, 맞출 수 없으면 이름 없음).
**비교용 키**(`accounts.display_name_key`): NFKC(전각 · 반각 · 호환 문자 · 결합 문자) → 소문자 → NFKC. `Ann` · `ANN` · `Ａｎｎ` 가 같다. 키릴 `а` 와 라틴 `a` 같은 **동형 문자까지 같게 보지는 않는다** — 스푸핑 방지가 목적이면 앱이 `reserved` 와 운영 모니터링을 더한다.

**저장 — 스키마는 방식과 무관하다.** `accounts` 에는 `display_name_key varchar(255)` · `display_tag char(4)` 와 유니크 `(display_name_key, display_tag)` 가 **언제나** 있고, 방식은 무엇을 저장하느냐로만 갈린다. 키는 닉네임이 있으면 방식과 무관하게 저장한다(방식을 나중에 바꿔도 다시 계산하지 않는다). 유니크 키에서 NULL 은 서로 겹치지 않는다(PostgreSQL · MySQL 둘 다 — `JdbcDisplayNameDbTest`):

| 방식 | `display_tag` | 효과 |
|---|---|---|
| `NONE` | `NULL` | 유니크에 안 걸린다 — 같은 닉네임 여럿 |
| `UNIQUE` | `'0000'` 고정 | 키 하나에 하나. `0000` 은 "꼬리표 아님" 표시라 응답의 `displayTag` 는 `null` |
| `TAGGED` | `'0001'`..`'9999'` | 키 안에서 겹치지 않는 무작위 꼬리표 |

- **언제 확인하나**: 프로필 수정(`PATCH /account/me`)과 **가입 확인을 끝낼 때**(`POST /auth/verify-email` — 계정이 만들어지는 순간). 가입 **요청**(202)은 닉네임이 쓰였는지 알리지 않는다 — 닉네임이 쓰였는지는 게시판에 보이는 공개 정보라 확인 시점의 `409` 는 괜찮지만, 계정 존재 여부는 여전히 어느 응답에도 드러나지 않는다. 필수 · 형식 검사(`400`)는 요청 본문만 보고, 주소가 있든 없든 **같은 시점 · 같은 응답**이다 (`DisplayNameFlowsTest` · `DisplayNameWebTest`). 확인 때의 `409` 는 코드를 이미 썼으므로 다른 닉네임으로 처음부터 다시 가입한다.
- **TAGGED**: 새 닉네임마다 키 안에서 무작위 꼬리표를 뽑는다. 겹치면(경합 포함 — 먼저 읽고 쓰지 않고 유니크 제약이 가른다) 다시 뽑고, 몇 번 겹치면 키 안의 빈 꼬리표를 직접 찾고(`displayTagsOf`), 9999 개가 다 차면 `409 DISPLAY_NAME_TAKEN`. 닉네임을 **바꾸면** 꼬리표를 새로 뽑는다 — 키가 그대로(대소문자 · 전각만 바뀜)면 지킨다.
- **탈퇴와 닉네임**: 유예 중(`DELETED`)에는 키 · 꼬리표가 **잡혀 있다**(주인이 돌아올 수 있다). 지워지면(`ERASED`) 이름 · 키 · 꼬리표가 모두 NULL 이 되어 풀린다. `ERASED` 행에는 닉네임을 쓰지 않는다(저장소가 거절).
- **작성자 이름 조회**: board 같은 모듈은 이 모듈을 모른 채 platform 의 `AuthorDirectory` 로 이름을 묻는다. 기본 구현(`AccountAuthorDirectory`, `@ConditionalOnMissingBean`)은 닉네임 · 꼬리표를 `where id in (…)` **한 번**으로 준다. 상태별: `ACTIVE` · 유예 중(`DELETED`) · `SUSPENDED` 는 행이 살아 있으니 **이름 그대로**(유예 중인 주인은 돌아올 수 있고, 정지된 계정의 글을 누가 썼는지 숨기면 운영자도 읽기 어렵다), `ERASED` 는 이름 없음(board 쪽은 이미 톰스톤). 돌판(팬덤)마다 다른 닉네임처럼 범위별 이름이 필요한 앱은 같은 타입의 빈을 두면 기본 구현이 물러난다 — `AuthorContext(source="board", scope=<게시판 코드>)` 로 어느 게시판에서 묻는지 알 수 있다.

**방식을 바꿀 때 (이미 계정이 있는 DB)** — 아무것도 안 하면 안전하지만 옛 계정은 새 방식의 보호를 못 받는다. 먼저 중복을 본다: `select display_name_key, count(*) from accounts where display_name_key is not null group by 1 having count(*) > 1`.

| 바꿈 | 아무것도 안 하면 | 옛 계정까지 적용하려면 |
|---|---|---|
| `NONE` → `UNIQUE` | 옛 닉네임은 `display_tag` 가 NULL 이라 유니크에 안 잡힌다 — 새 사람이 옛 사람의 이름을 쓸 수 있다 | 중복을 먼저 고친(운영자가 이름을 바꾼) 뒤 `update accounts set display_tag = '0000' where display_name_key is not null and display_tag is null` — 중복이 남아 있으면 유니크 위반으로 **실패**한다(그것이 점검이다) |
| `NONE` → `TAGGED` | 옛 계정은 꼬리표 없이 보이고 새 계정만 꼬리표를 받는다. 충돌은 없다 | PostgreSQL: `update accounts a set display_tag = lpad(r.n::text, 4, '0') from (select id, row_number() over (partition by display_name_key order by created_at, id) n from accounts where display_name_key is not null and display_tag is null) r where a.id = r.id` · MySQL 8: `update accounts a join (…같은 select…) r on r.id = a.id set a.display_tag = lpad(r.n, 4, '0')` (같은 키가 9999 를 넘으면 안 된다) |
| `UNIQUE` → `TAGGED` | `'0000'` 인 옛 계정은 꼬리표 없이 보이고 새 계정은 꼬리표를 받는다 | 위 `TAGGED` 백필을 `display_tag = '0000'` 에 대해 |
| `TAGGED` → `UNIQUE` / `NONE` | 이미 꼬리표를 가진 계정은 꼬리표가 계속 보인다. `UNIQUE` 는 옛 `닉네임#0417` 과 새 `닉네임` 을 같은 이름으로 보지 않는다 | `TAGGED` → `UNIQUE` 는 겹치는 이름을 먼저 정리한 뒤 `update accounts set display_tag = '0000' where display_name_key is not null`. 꼬리표를 걷어내려면 `update accounts set display_tag = null` (`NONE`) |

**닉네임 열이 없던 DB**(이 기능 이전의 `accounts` 마이그레이션을 이미 적용한 DB — 파일을 제자리에서 고쳤다): `flyway repair` 로는 열이 생기지 않는다. `alter table accounts add column display_name_key varchar(255), add column display_tag char(4), add constraint uq_accounts_display_name unique (display_name_key, display_tag)`(MySQL 은 키 열을 `character set utf8mb4 collate utf8mb4_bin` 으로) 를 직접 돌리고, 이름이 있는 계정의 키를 채운다: `update accounts set display_name_key = lower(display_name) where display_name is not null` — SQL 의 `lower` 는 NFKC 가 아니라서 **전각 · 호환 문자가 든 이름은 키가 달라** `UNIQUE` 의 비교에서 빠진다(그 계정이 이름을 바꾸면 바로잡힌다). 새로 만든 DB 와 stamped 프로젝트의 새 마이그레이션에는 필요 없다.

**위협 · 개인정보**: 닉네임은 공개 정보다(게시판에 보인다) — `UNIQUE` 의 `409` 와 응답의 이름은 비밀이 아니다. 계정이 있는지는 닉네임으로도 알 수 없다(요청 단계는 같은 202). 자동 닉네임은 이메일 · 계정 id · 시각에서 만들지 않는다(무작위). 탈퇴한 계정의 이름 · 키 · 꼬리표는 지워진다 — `AccountJourneyIntegrationTest` 의 전체 표 스캔(`display_name_key` 의 소문자 값 포함)이 본다.

## 삭제 · 지우기 · 내보내기

### 삭제 수명주기

```
 ACTIVE ──삭제 요청(다시 인증)──▶ DELETED ─────── grace(30d) 끝 + 주기 정리 ───────▶ ERASED   (deletion.mode=ANONYMIZE, 기본: 행은 남고 개인정보만 지움)
   ▲   ▲                          │  │                                           └▶ (행 없음) (deletion.mode=DELETE)
   │   └── 관리자 restore ────────┘  └─ self-restore(로그인 성공 + 취소 토큰) ─▶ ACTIVE
   │
   └─ 관리자 정지 ─▶ SUSPENDED ──정지 해제──▶ ACTIVE  (탈퇴 대기 중에 정지됐다면 DELETED 로 돌아가 탈퇴가 이어진다 — 유예는 새로)
                        └────── 관리자 erase (유예 없이, 재가입 차단 해시를 남김) ──▶ ERASED
```

- **왜 `ANONYMIZE` 가 기본인가**: 주인의 결정 — 개인정보만 지우고 계정 행은 남긴다. 다른 표(앱의 주문 · 결제 · 감사 …)가 `accounts.id` 를 들고 있어도 참조가 끊기지 않고, `DELETE` 처럼 행을 지우려다 연결고리를 놓칠 일이 없다. 두 모드 모두 안전하지만 `ANONYMIZE` 가 **되돌릴 수 없는 실수가 없는** 쪽이다(`DELETE` 는 `restrict` 외래키가 있는 앱에서 정리가 실패할 수 있다). 계정 id 를 어디에도 남기지 않을 앱만 `DELETE` 를 고른다.
- **`ERASED` 는 마지막 상태다**: 로그인 · 복구(`410 ACCOUNT.ERASED`) · 역할 부여 · 이메일로 찾기 · `me` 가 모두 안 된다. 같은 이메일로 새로 가입하면 **새 계정 id** 를 받는다(지운 행을 쓰지 않는다). 같은 제공자 주체의 소셜 로그인도 새 계정이다(로그인 수단 행이 없다). 관리자 목록은 기본으로 `ERASED` 를 빼고 `?status=ERASED` 로만 보이며 개인정보 열은 없다.
- **남는 것 / 지워지는 것 (`ANONYMIZE`)**

| 남는 것 | 지워지는 것 |
|---|---|
| `accounts.id`, `created_at`, `status=ERASED`, `erased_at`, `deleted_at`(탈퇴를 요청한 시각), `updated_at`(= 지운 시각), `email_verified=false` | `email`(→ NULL, 유니크 키에서 풀려 같은 주소가 새로 가입한다 — 두 DB 시험), `display_name` · `display_name_key` · `display_tag`(→ NULL, 닉네임도 풀린다), `locale`, `time_zone`, `suspended_reason`, `last_login_at`, `purge_after` |
| `account_audit` 의 **사건 줄**(종류 · 시각 · 계정 id) | 같은 계정 줄의 `ip` · `detail` (→ NULL). 다른 계정 줄은 그대로 |
| (운영자 지우기만) `account_blocks` 의 해시 | 로그인 수단 전부(비밀번호 해시 · 제공자 주체 · 매직 링크), 역할, `account_tokens`(계정 id 로 걸린 것 + 그 주소가 주인인 것), `account_challenges`(계정 id · 그 주소의 가입 시도 — IP 포함), 이메일 변경 대기 |
| 각 모듈이 정한 것: board 는 작성자를 `deleted:<해시>` 로, legal 은 동의 기록의 사람을 지우고 증거는 남김(`erasure.mode`) | auth-session 의 세션 · 리프레시 토큰(IP · UA · 기기 이름), notification-jdbc 의 받은편지함 |

- 정리는 **계정 하나당**: ① 조건 재확인 + **선점**(`claimErasure`, 리스너 전) ② 다른 모듈의 고리 ③ 한 트랜잭션(`AccountRepository.erase` · `purge`: 계정 행 락 → 조건 재확인 → 위 표의 행들 → 계정 갱신 · 삭제). `account-jdbc` 의 `erase` · `purge` 는 `account_tokens` · `account_challenges` · `account_audit` 를 **같은 트랜잭션에서 직접** 지운다 — 그 표들의 저장소(`OneTimeTokenStore` · `ChallengeStore`)를 앱이 바꿨다면 그 앱의 `AccountRepository.erase` 가 자기 표를 같이 지워야 하고, 못 지운 줄은 서비스가 저장소 호출 전에 닫는 것(`closeSensitiveLinks`) + 만료 청소(`cleanup.expired-retention`)가 마지막 방어다. 다른 모듈의 고리는 그 앞에서 **모두** 불리고, 하나라도 실패하면 계정은 `DELETED` 그대로 다음 주기에 다시 한다 — 고리는 멱등이어야 한다.
- 한 번에 `deletion.purge-batch`(50) 개까지, 여러 인스턴스는 `account_locks` 임대를 쥔 하나만 정리한다 (잡 큐가 있으면 주기마다 한 인스턴스만 잡 `account-purge` 를 넣는다).
- **만료된 코드 · 토큰 청소**: `cleanup.expired-retention`(기본 1d) 이 지난 줄을 같은 주기 정리가 지운다. **만료는 읽을 때 검사**하므로 정리가 늦거나 꺼져 있어도 만료된 코드가 쓰이지는 않는다 — 이 값은 표가 쌓이지 않게 하는 청소 기준일 뿐이다.

### 정지는 박제다

- 정지된 계정은 탈퇴를 요청할 수 없다(`403 ACCOUNT.SUSPENDED_CANNOT_DELETE`). 탈퇴 유예 중에 정지되면 유예가 끝나도 **지워지지 않고** 막힌 채 남는다 — 관리자가 정지를 풀면 탈퇴가 이어지고(유예는 새로 시작), 명시적으로 `POST /admin/accounts/{id}/erase` 하면 지워진다.
- 로그인 수단 행이 남으므로 같은 이메일 · 같은 제공자 주체로 새 계정을 만들 수 없다 — 소셜 로그인은 같은 계정의 `AUTH.ACCOUNT_SUSPENDED`, 코드로 가입한 미확인 정지 계정을 메일함 증명으로 이어받는 길은 `403 ACCOUNT.REGISTRATION_BLOCKED`.
- **운영자가 정지 계정을 지우면** 재가입 차단이 남는다: 이메일과 각 제공자 주체의 **HMAC-SHA256 해시**(서버 비밀 `blocks.secret`)만 `account_blocks` 에 사유 · 시각 · 만료와 함께 둔다 — 원문은 어디에도 없다. 가입 **요청**은 늘 같은 202(존재 · 차단 여부가 드러나지 않는다), **메일함을 증명한 사람**(코드 확인 · 매직 링크) 또는 **제공자 계정을 증명한 사람**(소셜)만 `403 ACCOUNT.REGISTRATION_BLOCKED` 를 본다(이메일 확인을 끈 앱의 가입은 "이미 있는 주소" 와 같은 `409 EMAIL_TAKEN`). 보존은 `blocks.retention`(기본 `0` = 운영자가 지울 때까지), 관리자가 `GET/DELETE /admin/accounts/blocks` 로 보고 푼다.
- ⚠ **개인정보 처리방침에 적을 것(앱의 몫)**: ① 탈퇴 뒤에도 계정 id · 가입 시각 · 탈퇴 시각 · 감사 사건(IP 없이)이 남는다 ② 운영자가 이용 제한 중인 계정을 지우면 재가입을 막으려고 이메일 · 로그인 계정의 **되돌릴 수 없는 해시**를 `blocks.retention`(기본 무기한) 동안 보관한다 ③ 앱이 쓰는 다른 표(주문 · 결제 …)의 보존은 앱이 정한다. **차단 해시의 키**: `blocks.secret`(환경변수 `<P>_ACCOUNT_BLOCKS_SECRET`, 선언의 `secrets:` — 무작위로 충분)이 비면 **JWT 비밀에서 파생**한다. 그러면 **JWT 비밀을 돌릴 때 모든 차단이 조용히 풀린다**(해시가 달라져 맞지 않는다) — 그래서 관리자 API 를 켠 앱은 보호 환경에서 이 값이 비면 `DeployGuard` 경고가 난다(기동을 막지는 않는다: 차단을 쓰지 않는 앱도 있다). `blocks.secret` 자체를 바꿔도 기존 차단은 맞지 않으므로 처음부터 따로 두고 바꾸지 않는다.

### 탈퇴 취소 (`deletion.self-restore`)

- 기본 `false` — 이전과 같다(탈퇴한 계정은 존재하지 않는 계정처럼 보이고 복구는 운영자만). 스켈레톤은 사용 방식을 정하는 값을 켜 둔 채 내놓지 않는다(`outOfOrder` 등과 같은 원칙). **많은 서비스가 쓰는 방식이라 권장**하지만 로그인 응답이 하나 늘어나므로(프론트의 "탈퇴를 취소할까요?" 화면) 앱이 yml 에서 켠다.
- 켜면: 유예 중인 계정의 주인이 **올바른 방법으로 로그인에 성공**하면(비밀번호 · 매직 링크 · 소셜) 세션을 만들지 않고 `403 AUTH.ACCOUNT_DELETION_PENDING` + `data.purgeAfter` · `data.restoreToken`(15분, 한 번) 을 준다. 틀린 비밀번호는 일반 `401` 이다(존재 여부가 새지 않는다). 프론트가 `POST /account/delete/cancel {restoreToken}` 을 부르면 계정이 되살아나고 보통 로그인 응답(토큰)이 온다 + 안내 메일 + `DELETION_CANCELLED` 이벤트. **정지된 계정은 해당 없음**(`AUTH.ACCOUNT_SUSPENDED`).
- **되살아나는 상태**: 확인된(또는 주소 없는) 계정은 `ACTIVE`, 확인 전이면 `PENDING_VERIFICATION` — 단 `sign-up.email-verification=false` 인 앱에는 확인 단계가 없으므로 늘 `ACTIVE`(취소 · 운영자 복구 · 정지 해제 모두 `AccountCore.reopenedStatus`).
- 계약: `docs/account-http-contract.md` §8.

### 지우는 고리 · 내보내기

- 지울 때 `AccountErasureListener`(platform 의 공용 계약)가 모두 불린다. 구현: **board** (글 · 댓글 작성자와 반응의 계정을 `deleted:<해시>` 톰스톤으로 — 행 · 카운터는 남고 응답에 `authorDeleted: true`), **notification-jdbc** (받은편지함 줄 삭제 + 다른 수신자 줄 안의 계정 id 를 톰스톤으로), **auth-session** (세션 · 리프레시 토큰 행), **legal** (동의 기록의 사람을 지우고 증거는 남김 — `erasure.mode: ANONYMIZE|DELETE`). 모듈마다 자기 데이터를 지우고 · 모든 고리는 **멱등**이어야 한다.
- **한계**: 다른 사람의 알림 본문에 삭제된 사람의 글 내용이 있으면 그것은 알림을 만든 모듈의 몫이다. `storage` 의 업로드는 이 구현에 없다 (앱이 고리를 구현한다).
- **데이터 내보내기**: `AccountDataExporter` 인터페이스만 있다 (엔드포인트 · 기본 구현 없음 — 법적 요구에 맞춰 앱이 구현).

## 메일

템플릿 ko · en (`AccountMailTemplates` 빈으로 교체), 계정 로케일로 고르고 없으면 `mail.default-locale`. 링크(재설정 · 매직 링크)의 앞부분은 `mail.link-base-url`; 가입 · 이메일 변경 · 다시 인증 · 삭제는 링크가 없는 코드 메일이다. 전달 길은 `notification-mail` 의 `MailSender`; 없으면 `LogOnlyMailTransport` 가 **보내지 않고** 알린다. 링크(토큰)는 앱이 `mail.log-links` 를 켠 로컬에서만 로그에 나온다 (보호 환경에서 켜면 DeployGuard 문제). 토큰은 어떤 이벤트 · 로그 줄에도 없다.
로컬 개발은 compose 의 `mail` 프로필(mailpit, `localhost:8025`)이 받는다 — `apps/sample` 이 그렇게 쓴다.

## 이벤트 · 감사 · 경보

모든 인증 사건(가입 · 확인 · 로그인 성공/실패/제한 · 비밀번호 · 세션 · 연결/해제 · 정지 · 역할 · 삭제 · 지움)은 `AccountEventPublisher` → `AccountEventListener` 빈들로 간다 (기본: 한 줄 로그, 이메일 · 토큰 없음). `skeleton.account.audit.enabled=true` + `account-jdbc` 면 `account_audit` 표에도 쓴다. `alert` 모듈이 있으면 로그인 시도 폭주 · 리프레시 토큰 재사용이 주인 경보로 간다.

## 배포 가드 (`skeleton.env=stage|prod` 또는 auth 의 보호 프로필)

기동이 막히는 것: `skeleton.web.client-ip.mode` 미설정 · 메모리 계정 · 토큰 저장소 · 메모리 세션 저장소 · 메일 발송 길 없음 · `mail.link-base-url` 비어 있음 · `mail.log-links=ON` · 시드 계정 · 캡차를 필수로 했는데 검증기 없음 · 쿠키 전달인데 `cookie.secure=false` · 잘못된 부트스트랩 이메일. 기동이 막히는 것에 메모리 `ChallengeStore`(코드가 재시작에 사라지고 인스턴스끼리 못 나눈다)도 있다. 경고: 가입이 열려 있는데 캡차 없음 · 로그인 제한 끔 · 이메일 확인 끔(+ 매직 링크 · 소셜 병합과 함께면 따로).

## HTTP 계약

프론트가 쓰는 계약 전체(엔드포인트 · 요청/응답 JSON · 오류 코드 · 순서)는 [account-http-contract.md](account-http-contract.md).

## 정해 둔 것 (주인이 뒤집을 수 있는 것)

- **bcrypt(기본) vs argon2**: 추가 의존이 없고 해시가 메모리를 쓰지 않아 로그인 폭주 때 메모리 DoS 면이 작다. argon2id 는 opt-in(`password.encoder=argon2` + BouncyCastle). 델리게이팅 인코더라 `{bcrypt}` 접두사로 저장되고 옛 접두사 없는 bcrypt 도 검증되며 로그인 때 새 방식으로 올라간다.
- **리프레시 전달 기본 body**: 쿠키가 없어 CSRF 면이 없고 react `api-client` 가 바로 쓴다. 대가는 XSS 가 토큰을 읽을 수 있다는 것 — 회전 · 재사용 탐지 · 짧은 액세스 토큰이 피해를 줄인다. 쿠키가 더 낫다고 판단하면 `delivery: COOKIE`.
- **로그인 한도는 시도 전부**를 센다 (위 위협 표).
- **뒤로 넘기는 일(메일 · 재설정 조회)은 프로세스 안의 작은 풀**이다 — 종료 때는 기다려 마치고 넘치면 부른 스레드가 하지만, 비정상 종료로 처리 못 한 메일은 사라지고 사용자가 다시 요청한다(재전송). **가입 계정 행은 요청 스레드가 저장한다**(202 를 받은 가입은 사라지지 않는다). 내구성이 더 필요하면 `AccountTaskRunner` 빈으로 잡 큐에 넣는다.
- **액세스 토큰은 상태 없는 JWT** — 정지 · 역할 변경은 일반 API 에는 최대 15분 늦게 반영된다 (새로고침은 즉시 현재 역할). **관리자 API 는 매 호출 저장소를 다시 읽는다.**
- **`username` = 이메일** (이 모듈이 만든 계정에는 따로 사용자 이름이 없다).
- **탈퇴 뒤 기본은 `ANONYMIZE`** (개인정보만 지우고 행은 `ERASED` 로 — 주인의 결정: "연결고리가 끊기면 안 된다"). `DELETE` 로 바꾸면 행까지 지운다. **정지는 박제**(탈퇴로 못 빠져나가고, 운영자가 지우면 해시 차단이 남는다). **탈퇴 취소(`self-restore`)는 기본 꺼짐**(스켈레톤은 사용 방식을 정하지 않는다) — 앱이 켠다.

## 약관 동의 (`legal` 모듈이 있을 때)

가입 요청의 `consents: [{type, version, locale?}]` 는 가입 시도에 실려 저장되고, 코드가 확인되어 계정이 만들어질 때 **같은 트랜잭션에서**(`AccountTransaction`) 기록된다 — 확인되지 않은 시도는 동의를 남기지 않는다. 검사(`SignUpConsentGate.check`)는 요청 본문과 문서만 보므로 응답은 주소와 무관하게 같다. `legal` 이 없으면 `consents` 는 무시된다. 소셜 · 매직 링크 첫 로그인은 동의 없이 계정이 만들어지고 재동의 필터 · `GET /consents/me` 로 세션이 온전히 쓰이기 전에 받는다. 자세히: [legal.md](legal.md), [legal-http-contract.md](legal-http-contract.md).
