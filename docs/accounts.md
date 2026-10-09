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
| `auth-social` (확장) | 제공자가 확인한 이메일인지(`emailVerified`)와 제공자가 그 주소의 **권위자**인지(`emailAuthoritative`, 기본 `emailVerified` 와 같다)를 프로필에 싣는다 | 계정 연결 · 병합 규칙이 권위자 값을 믿는다 |
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

**이미 계정이 있는 주소로 가입 요청**: 화면 응답은 새 주소와 같은 `202`(+ 같은 모양의 `signUpId` · `expiresAt` · `resendAvailableAt`). 메일은 코드 대신 "이미 계정이 있어요" — 기본은 **토큰 없는 링크만**이다: **로그인 페이지 링크**(`mail.link-base-url` + `mail.login-path`) · **가입 수단 문장**("구글로 가입되어 있어요.") · "비밀번호를 잊었다면" **재설정 요청 페이지 링크**(`mail.link-base-url` + `mail.forgot-path`, 기본 `/forgot-password` = react-skeleton 견본의 경로 — 메일을 받은 사람이 거기서 **자기가** 재설정을 요청한다). 이 메일은 **남이 넣은 요청**이 만든다 — 토큰을 만들면 (a) 주인이 방금 받은 재설정 링크가 닫히고(새 토큰이 이전 것을 닫는다) (b) 가입 요청 3번으로 `reset:email` · `magic-link:email` 한도가 바닥나 주인이 정작 필요할 때 링크를 못 받는다. 그래서 기본은 아무 토큰도 만들지 않고 한도도 쓰지 않는다. 정지된 계정에는 요청 페이지 링크도 없다(박제). 메일 한 통 자체는 주소별 메일 예산(3/시간)을 쓴다.
**토큰이 든 링크를 켜려면** `skeleton.account.sign-up.existing-account-mail.include-credentials-links: true`(기본 꺼짐; `apps/sample` 은 켜지 않는다): **최초 가입 요청**의 메일에만 비밀번호 **재설정 링크**(`forgot` 와 같은 토큰 · 같은 한도 · 비밀번호가 없는 계정은 이 링크로 첫 비밀번호를 정한다) · `auth-magic-link` 가 있으면 **1회용 로그인 링크**(`MagicLinkIssuer`)가 든다. 켜도 (1) 그 주소에 **이미 열린 토큰이 있으면 새로 만들지 않고 그 줄을 뺀다**(요청 페이지 링크가 대신 들어간다) — 열린 링크를 닫지도 한도를 쓰지도 않으므로 가입 요청을 아무리 보내도 한도가 **한 칸**만 줄고 주인이 연 링크는 그대로다 (2) **재전송**(`/account/verification/resend`)은 토큰을 다시 만들지 않는다 (3) 정지된 계정에는 주지 않는다. 남는 한계: 열린 토큰이 없을 때 첫 요청 하나는 주인이 요청하지 않은 링크를 주인의 메일함으로 보낸다(메일함 주인만 쓸 수 있는 링크이고 한 번 · 짧은 유효). 열린 토큰 확인과 발급 사이의 경주(두 요청이 동시에 와서 둘 다 "없음" 을 본다)는 뒤의 것이 앞의 것을 닫는 정도다 — 주인이 연 링크는 그 확인 전에 열려 있었다면 영향이 없다. (`AlreadyRegisteredMailTest` · `MagicLinkWebTest` · `MagicLinkAlreadyRegisteredLinkWebTest`, 문구는 `mail-golden/already_registered*.{ko,en}.*`).
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
| 소셜 · 제공자가 **확인한** 이메일이 기존 계정과 같다 | 모듈 기본: **병합 없이** `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT` — 기존 방법으로 로그인해 설정에서 연결한다. `social.merge-on-verified-email=true`(모듈 기본은 꺼짐이고 `apps/api` · `apps/sample` 도 끈 채다 — 켜려면 앱 yml 에서 명시적으로)면 제공자가 확인한 이메일(**권위자**여야 한다 — Google 은 `@gmail.com`/`googlemail.com` 이거나 `hd` == 주소 도메인인 Workspace 일 때만, 비-Gmail · `hd` 없는 주소는 `email_verified: true` 여도 병합 안 됨(`auth-social-google` 의 `email-trust`, 기본 `AUTHORITATIVE`); Kakao: 확인 **+ 유효**, Naver · **LINE · X**: 확인 정보가 없어(또는 문서가 보증하지 않아) 병합 안 됨 — `auth-social-oidc` 의 `email-trust` · `auth-social-x` 의 `trust-confirmed-email` 이 기본 미확인)이 **글자 그대로** 같을 때 그 계정에 붙고(프론트는 409 가 아니라 200 로그인을 본다) 계정 주소에 알림 메일 + `IDENTITY_LINKED` 이벤트. 기존 계정이 **미확인**이면 제공자의 확인이 메일함 증명이라 남이 심은 자격을 모두 버린다(위 "메일함 증명"). **정확한 규칙(주인용)**: 기본 모드에서는 미확인 계정이 **존재하지 않는다**(가입 시도는 계정이 아니다) — 병합은 늘 **확인된 계정**에만 붙고, 미확인 계정에 붙는 일은 `email-verification=false` 모드에서만 있으며 그때도 안전하다(증명이 남의 자격을 지운다). **권위자가 아닌 주소** (확인했다고만 한 주소): 병합도 메일함 증명도 하지 않고, 기존 계정과 겹치면 같은 `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`, 겹치지 않으면 **주소 없는 새 계정**이 만들어진다(주소를 저장하면 진짜 주인이 "이미 계정이 있어요" 에 막히거나 재설정이 낯선 계정으로 이어진다). 로그인한 뒤 설정에서 하는 **수동 연결**은 권위자가 아니어도 된다(본인이 두 쪽을 증명했다). **남는 위험**: 권위자로 인정한 주소도 Gmail 주소 재사용·Workspace 도메인 이전 같은 일이 이론상 있다 — 비밀번호 재설정과 같은 신뢰 가정이다. `email-trust: VERIFIED_CLAIM` 으로 풀면 비-Gmail 주소의 옛 주인이 새 메일함 주인의 계정에 붙을 수 있다 |
| 소셜 · 제공자가 확인하지 **않은** 이메일 | 이메일을 없는 것으로 친다 — 저장하지 않고(남의 주소 선점 방지) 충돌도 알리지 않는다(주소 존재 조회 방지) |
| 매직 링크(메일함 증명) · 같은 이메일의 기존 계정 | 그 계정에 붙는다(`sign-up=false` 여도 — 가입이 아니다) + 이메일 확인으로 친다. 계정이 **미확인**이었다면(확인 없는 가입 모드) 남이 심은 모든 수단 · 코드 · 세션을 **버린다** (사전 탈취 방어) |
| 마지막 "들어오는 길" 을 떼려 한다 | `409 ACCOUNT.LAST_SIGN_IN_METHOD` (원자적 — 동시 해제 둘이 다 성공하지 않는다) |

## 이메일 정규화 (한 곳)

`Emails.normalize` = 앞뒤 공백 제거 → 유니코드 NFC → 소문자(`Locale.ROOT`). 점 · `+태그` 제거나 NFKC · IDN 변환은 **하지 않는다** (같게 보면 남의 주소를 같다고 말하게 된다). 저장 · 조회 · 한도 키 · 토큰 주인 · 메일 수신자가 모두 이 값이고, 같은 주소인지는 DB 정렬에 맡기지 않고 `AccountCore.accountByEmail` 이 **저장된 글자와 글자 그대로** 비교한다. MySQL 은 `email` · 토큰 `subject` 열도 `utf8mb4_bin` 이다 (기본 정렬은 악센트 · 대소문자를 같게 본다). 메일은 늘 계정에 **저장된** 주소로 간다.

## 위협 모델 — 각 항목을 덮는 시험

| 위협 | 막는 법 | 덮는 시험 |
|---|---|---|
| **계정 열거 (응답)** | 가입 · 재설정 · 재전송 · 매직 링크 요청은 계정 유무와 무관하게 같은 `202` 본문(가입은 `status` + 같은 모양의 `signUpId`). 이미 확인된 주소의 시도에는 코드 대신 "이미 계정이 있어요" 메일(토큰 없는 링크만 — 기본)이 가고 코드는 누구도 모르므로, 코드 입력은 새 주소의 틀린 추측과 똑같이 줄어들다 끝난다 | `AccountWebTest` `sign-up answers 202 with an opaque attempt id identically…` · `forgot is 202 and identical…`, `SignUpVerificationTest` `an already registered address looks the same…`, `MagicLinkWebTest` `request is 202 with the same body…` |
| **재전송을 다 쓴 시도의 죽은 버튼** | 재전송 횟수(`max-resends`)를 다 쓴 시도는 `resendAvailableAt: null`(키는 남는다)로 알린다 — 만료 뒤에 "지금" 을 주면 눌러도 아무 일 없는 버튼이 살아 있다. 이 신호는 **그 시도를 쥔 쪽**(진짜 `signUpId` 를 아는 브라우저)에게만 보이고 모르는 id 는 늘 값이 있는 가짜 창이다 | `CodeWindowTest` `an attempt whose three resends are used up…` · `ResendExhaustedWebTest` |
| **형식 오류가 IP 한도를 쓰는 문제 (가입)** | 닉네임 규칙 · 주소 모양 같은 **형식만 보는 검사는 `signup:ip` 한도 앞**이다 — 시도 · 메일 · 외부 호출을 만들지 않는 요청이 공용 IP 의 한도를 쓰지 않는다. 한도가 지키는 것(동의 조회 · 캡차 호출 · 비밀번호 정책(유출 조회가 외부일 수 있다) · 해시 · 시도 저장 · 메일)은 한도 **뒤**다 | `SignUpFormatBeforeLimitTest` |
| **계정 열거 (시간)** | 요청 스레드는 검사 · 한도 · 해시 한 번 · 시도 저장 한 번을 새 주소든 · 진행 중이든 · 있는 주소든 똑같이 하고(**계정 저장소는 읽지 않는다**) 코드 · 메일은 뒤에서. 로그인은 계정이 없어도 해시 비교 한 번 | `SignUpVerificationTest` `enumeration parity - the request thread does the same work…`, `AccountResponseLevelSecurityTest` `sign-up, forgot and resend answer without waiting for mail…`, `PasswordServiceTest` `forgot never touches the stores on the request thread…`, `PasswordLoginServiceTest` `unknown account still costs one hash comparison…` |
| **열거 (잠금 오라클)** | 로그인 한도는 입력 식별자를 계정이 있든 없든 똑같이 센다 | `LoginControlsTest` `the eleventh attempt on one identifier… whether or not the account exists` |
| **무차별 대입** | IP 당 · 주소당(`email` · `accountId` · 아이디로 읽히지 않는 `username`(아이디를 끈 앱의 `username`, `@` 가 든 입력)은 한 버킷) 시도 수 제한(429 + `Retry-After`). **아이디(`login-id.enabled`)로 읽힌 `username` 은 따로 `loginId:<정규화한 입력>` 버킷**(아래 "아이디 버킷" 행), 현재 비밀번호 추측도 계정당 제한, 경보. **IP 출처**: `skeleton.web.client-ip.mode` 를 정해야 한다 — 안 정하면 `X-Forwarded-For` 로 한도가 풀리므로 stage · prod 가드가 기동을 막는다 | `LoginControlsTest`, `EmailNormalizationTest` `the login limit key is one per address…`, `AccountDeployGuardTest` `without a client IP mode…`, `AccountWebTest` `login attempts are throttled…`, `PasswordServiceTest` `guessing the current password…` |
| **아이디로 이메일 ↔ 아이디 확인 (한도 오라클)** | 아이디 입력의 한도 버킷은 계정이 있든 없든 `loginId:<입력>` 이고 저장소를 읽지 않는다 — 이메일 버킷과 합치면 "이메일 버킷을 채운 뒤 후보 아이디가 429 면 그 계정의 아이디"를 맞혀 볼 수 있다. 대가: 한 계정의 추측이 창마다 이메일 몫 + 아이디 몫이다 | `LoginControlsTest` `with the email bucket full a known id and an unknown id are answered the same…` · `with the id bucket full…`, `LoginIdWebTest` `L5 - with the email's allowance spent…` |
| **아이디를 영구히 태우기 (정하기 → 버리기 → 재로그인 반복)** | 정하기 한도는 계정별 + **IP 별**(`login-id-claim:ip`)이고 형식을 통과한 시도(성공 · 실패)를 센다. 대가: 한 IP 뒤 사용자들이 한도를 같이 쓴다 | `LoginIdClaimTest` `the same IP spends one claim allowance across accounts…`, `LoginIdRequiredWebTest` `I3 - …` |
| **긴 아이디 입력으로 정규식 재귀 (공개 경로)** | 모양을 보기 전에 길이(열 길이 30)로 거절하고 한도 키도 짧게 줄인다 — 100KB 입력이 500 · 스택 로그를 만들지 않는다 | `LoginIdRulesTest` `an input far longer than the column…`, `LoginIdWebTest` `I1 - …` ×2, `LoginControlsTest` `a huge login id input is counted by a short key…` |
| **아이디 존재 열거 (가입 · 실시간 확인 · 찾기)** | 가입의 아이디 판정은 아이디만 보고 이메일 존재와 무관한 같은 응답 · 시점. 실시간 확인은 예약어 · 살아 있는 · 탈퇴한 아이디를 한 답(`TAKEN`)으로, 같은 조회로 한다. 찾기는 늘 202 이고 조회 · 메일은 뒤에서. **남는 위험**: "이 아이디가 있나" 콕 집어 한 번은 막지 못한다(위 "남는 위험") | `LoginIdSignUpTest` `a reserved id (symbols ignored), a live id and an erased account's id give the same 409…`, `LoginIdAvailabilityTest` `the three reasons do the same repository work…`, `LoginIdReminderTest` `the request thread never touches the stores…` |
| **아이디 비공개 (응답 · DB · 로그)** | 아이디가 나가는 곳은 `me` · 아이디 찾기 메일 · 관리자 응답 셋뿐. 응답 전수 스캔 + 응답 타입 가드 + DB 전수 스캔 + 요청 DTO `toString` 가림 | `LoginIdLeakIntegrationTest`(apps/sample), `JdbcErasureDbTest`, `SecretsStayOutOfToStringTest`, `LoginIdAdminWebTest` |
| **탈퇴한 아이디의 재사용** | 정하는 순간 넣는 해시 표(키 있는 HMAC) — 어느 삭제 길이든 해시가 남는다. 보호 환경에서 `blocks.secret` 이 비면 기동을 막는다 | `JdbcLoginIdDbTest`, `AccountDeployGuardTest` |
| **아이디 게이트 우회 / 두 게이트의 서로 열기** | 허용 목록 하나를 두 게이트가 같이 쓰고(코드 고정) `extra-exclude-paths` 는 게이트별이다 — 이메일 쪽 extra 가 아이디 게이트를 열지 않는다. 경로 읽기 · 핸들러 종류 · HEAD 는 이메일 게이트와 같다 | `LoginIdRequiredInterceptorTest` · `LoginIdRequiredWebTest` `L7 - the email gate's own extra paths do not open the id gate` |
| **쓰기 때 방식: 메서드 속임 · 결제 열기** | 읽기 메서드는 `GET` · `HEAD` · `OPTIONS` 글자 그대로(대소문자 구분)이고 메서드 덮어쓰기 헤더 · `_method` 에 속지 않는다. 결제는 허용 목록에 넣지 않는다 — 기본이 막힘이고 견본 앱은 변경 핸들러 전수 시험이 지킨다. **남는 위험**: 소켓으로 들어오는 쓰기 메시지 · 상태를 바꾸는 `GET` 은 이 게이트 밖이다 | `EmailOnWriteWebTest` `a method override cannot turn a write into a read…`, `EmailOnWriteInterceptorTest`, `WriteGateCensusTest` |
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
| **재전송 응답의 시각으로 시도 존재 열거** | 모르는 · 지워진 `signUpId` 에는 id 와 서버 비밀(HMAC)로 정한 **가짜 발급 시각**에서 `expiresAt` · `resendAvailableAt` 을 만들어 준다 — 같은 id 는 쿨다운 안에서 호출마다 같은 값(진짜 시도가 쿨다운 안에서 그러듯)이고 쿨다운이 지나면 다음 걸음으로 간다. 이전에는 모르는 id 는 늘 `지금 + ttl` 이라 두 번 불러 값이 움직이면 가짜였다. 한계: 쿨다운 뒤 진짜 시도는 **호출한 순간**에 재발급되고 가짜는 자기 걸음을 따르므로 이 차이로 가려내려면 진짜 시도 id 를 쥐고 정밀하게 시간을 재야 한다 — 256비트 id 라 "id 가 있는지" 를 아는 것은 거의 가치가 없다 | `CodeWindowTest` `an unknown attempt id gets the same window on every call inside the cooldown…` · `the window of an unknown id has the shape of a real one…` |
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
| **소셜 병합 탈취** | 확인된 이메일만 믿고 기본은 병합하지 않는다 (위 표) | `SignInServiceTest` `a verified provider email that matches…` · `merging is opt-in…` · `a provider email that is not verified is ignored…` · `a claimed-but-not-authoritative provider email never merges…` · `a new account from a non-authoritative address…`, `GoogleOAuthProviderTest` (권위자 판정 · 도메인 파싱 함정), `AccountSocialWebTest` |
| **비밀번호 · 토큰 · 코드가 로그에** | 서비스는 안 남기고, 요청/응답 DTO · 저장 행 `toString` 은 가린다(Spring MVC 가 DEBUG/TRACE 에서 본문 · 응답 객체를 `toString` 으로 찍는다 — 실측: 가입 id 가 응답 객체 로그로 샌 것을 잡았다). 기본 `LogMasker` 는 `token=` 쌍도 가린다 | `AccountResponseLevelSecurityTest` `no one-time token, code, sign-up id or password ever reaches the log…`, `MailPathLogLeakTest`, `SecretsStayOutOfToStringTest` |
| **권한 상승 · 관리자 사고** | 관리자 권한은 **매 호출 저장소의 계정**(ACTIVE + 역할)으로 확인 — 낡은 토큰으로 못 한다. 첫 관리자는 확인된 이메일 + ADMIN 이 아직 없을 때만, 마지막 ADMIN 은 정지 · 회수 · 삭제 불가(저장소가 원자적으로 판정 — 동시에 서로를 정지해도 0 이 안 된다), 자기 정지 불가, 비밀번호 기본값 없음, 시드 계정은 stage · prod 에서 기동 실패 | `RegistrationServiceTest` (bootstrap), `DeletionAndAdminTest` `two administrators suspending…`, `AccountCallersTest`, `JdbcAccountRepositoryDbTest` `two administrators demoting…` (두 DB), `AccountDeployGuardTest` |
| **로그인 잠금 DoS** | 시도 전부를 세므로 공격자가 남의 식별자를 10분 동안 잠글 수 있다 — **수용한 대가** (실패만 세려면 잠김 상태를 저장해야 하고 그것이 존재 오라클이 된다). 창 · 횟수는 설정 | `LoginControlsTest` |
| **삭제 뒤 남는 데이터** | 유예 뒤 모든 `AccountErasureListener` 가 성공해야 계정을 정리한다(하나라도 실패하면 다음 주기에 재시도, 한 계정의 실패가 같은 묶음의 다른 계정을 막지 않는다). 기본(`ANONYMIZE`)은 행을 `ERASED` 로 남기고 **개인정보 열 · 로그인 수단 · 역할 · 토큰 · 코드 · 감사 행의 IP · 상세**를 한 트랜잭션으로 지운다. 가입 시도 · 코드 행은 만료 뒤 같은 청소가 지운다 | `AccountErasureTest` (13+), `JdbcErasureDbTest` ×2 DB — 특히 `after erasure no column of any table still holds…` (모든 표 · 모든 열을 훑는 `PlantedDataScan`), `AccountJourneyIntegrationTest` (board · notification · sessions · consents 까지 모든 모듈 표), `DeletionAndAdminTest`, `AccountPurgeJobTest`, `NotificationInboxErasureDbTest`, `SessionMaintenanceTest`. **감사 표**(`audit.enabled` 일 때): 사건 줄(종류 · 시각 · 계정 id)은 남고 **그 계정 줄의 IP · 상세는 `ANONYMIZE` · `DELETE` · 운영자 지우기 모두에서 지운다**(`JdbcErasureDbTest` `the DELETE mode purge leaves the same nothing behind…` · `a forced purge…`). 계정 id 가 없는 줄(없는 계정에 대한 로그인 실패 …)은 이 사람의 것으로 가려낼 수 없어 **그대로 둔다**(`…cannot be attributed to the person and stay`) — 보존 기간은 앱이 정한다. `DELETE` 모드에서는 행이 없어 감사 줄에 계정 id 만 매달린 채 남는다 |
| **탈퇴 직후의 늦은 복구 · 지우기 경주** | 되살리기와 지우기는 둘 다 `status = 'DELETED'` 조건부이고 지우기는 계정 행 락 안에서 조건을 다시 본다 — 하나만 이긴다 | `JdbcErasureDbTest` `a restore racing the erasure at the end of the grace has exactly one winner` (두 DB) |
| **지워진 행에 다시 쓰기** | `ERASED` 행에는 저장소가 조건(`status <> 'ERASED'`)으로 아무것도 쓰지 않는다 — 지운 **뒤** 도착한 옛 액세스 토큰의 `PATCH /me`(이름 · 로케일 · 시간대), 지우기와 겹친 정지 해제, 이메일 변경, 로그인 수단 연결 모두. 서비스도 먼저 본다(`404`) | `ErasedRowStaysErasedTest` ×4, `AccountWebTest` `after an administrator erased the account, the owner's still-valid access token…`, `JdbcErasureDbTest` `nothing is written to an erased row…` (두 DB) |
| **지우기와 취소의 경계 경주 (리스너가 먼저 도는 문제)** | 지우기는 리스너(board 톰스톤 · 받은편지함 삭제)를 부르기 **전에** 계정 행을 **선점**한다(`claimErasure` — 계정 행 락 안에서 조건 확인 + `erase_claimed_at`). 선점된 계정은 되살리기 · 상태 변경이 거절된다 — 시계가 어긋난 취소가 이겨서 ACTIVE 인데 톰스톤이 찍힌 계정이 생기지 않는다. 리스너가 실패하면 선점은 남고 다음 주기에 다시 한다(유예는 이미 끝났다). 운영자 지우기도 같다(정지 해제가 거절됨, 실패는 `503 ACCOUNT.ERASURE_RETRY`) — 단 **첫 리스너가 성공하기 전에** 실패했으면 아무것도 지워지지 않았으니 선점을 되돌린다(`releaseErasureClaim` — 정지 해제가 그대로 된다). 일부가 성공한 뒤 실패했으면 선점을 유지한다: 정지 해제는 `409 ACCOUNT.ERASURE_IN_PROGRESS`(이벤트 없음)이고, **같은 지우기 호출을 다시 부르면 이어서 끝난다**(리스너는 멱등이라 처음부터 다시 돈다). 저장소가 거절한 정지 · 정지 해제는 204 · 이벤트를 내지 않는다 | `ErasureClaimTest` ×8, `JdbcErasureDbTest` `claiming the erasure…` · `an administrator's forced claim…` · `a claim racing a restore…` · `releasing a claim…` (두 DB) |
| **정지로 탈퇴해 빠져나가기** | 정지된 계정은 삭제를 요청할 수 없고(`ACCOUNT.SUSPENDED_CANNOT_DELETE`), 탈퇴 유예 중에 정지되면 유예가 끝나도 지워지지 않는다. 로그인 수단이 남아 같은 이메일 · 제공자 주체로 다시 가입할 수 없다. 운영자가 지울 때는 해시만 남는 재가입 차단이 생긴다 (아래) | `FrozenAccountTest` ×9, `SignInDepartedAndFrozenTest` (매직 링크 · 병합 소셜이 정지 계정에 수단을 붙이거나 로그인을 기록하지 않는다), `BlockedAddressBypassTest` (이메일 변경 확인 · 수단 연결로 차단 우회 불가), `JdbcAccountBlockDbTest` ×4 (두 DB), `AccountWebTest` `an admin erases a suspended account…` |
| **탈퇴 취소 토큰 오남용** | 취소 토큰은 로그인 **성공 뒤에만** 만들어지고(조회만으로는 만들지 않는다) 한 번 쓰고 · 15분 뒤 죽고 · 새로 만들면 이전 것이 죽고 · 다른 용도로는 못 쓰고 · 정지되면 못 쓴다. 주소당 시도 수를 센다 | `SelfRestoreTest` ×14, `SelfRestoreWebTest`, `MagicLinkSelfRestoreWebTest` (비밀번호로 가입해 매직 링크 수단이 없는 계정도 유예 중 매직 링크로 취소 · 유예가 끝난 계정에는 링크를 보내지 않는다) |
| **탈취된 세션이 첫 이메일을 심어 계정을 가로채기** | 위 "첫 이메일 등록의 재인증 면제" — 주소가 한 번도 없던 계정 · **그 로그인이 연 세션**이 `first-registration-window`(1시간) 안일 때만(세션 단위: 토큰 갱신은 세션을 옮기지 않고, 주인이 다른 기기에서 막 로그인해도 옛 세션의 창은 열리지 않는다) · 요청한 세션에서만 코드 입력 · 확인 시 다른 세션 종료 + `EMAIL_CHANGED` 이벤트 · 계정 한도. 창 밖 · 옛 세션 · 세션을 읽을 수 없는 구성(`auth-session` 없음) · 주소가 있던 계정은 평소의 재인증 | `FirstEmailRegistrationTest` (`a stale session does not inherit the window when the owner logs in somewhere else…` — 옛 세션 A · 주인의 새 로그인 B: A 는 `REAUTH_REQUIRED`, B 는 202 · `without a session store or with a closed or unknown session…` · `a new login - a new session - reopens the window…` · `after the window…` · `a zero window…` · `an account that already has an address…` · `the code is bound to the session that asked`), `EmailRequiredWebTest` `an old session cannot use the first-registration window…`, `SessionServiceTest` `openedAt…` (갱신이 시각을 옮기지 않는다) |
| **첫 이메일 등록으로 주소 존재 확인** | 이미 다른 계정의 주소여도 같은 202 · 같은 `expiresAt` / `resendAvailableAt`, 코드는 가지 않고 주인에게 토큰 없는 "이 주소로 이메일 등록을 시도했어요" 메일만 (가입의 "가입을 시도했어요" 문구는 상황과 안 맞아 따로 둔다) | `FirstEmailRegistrationTest` `an address that belongs to another account…` · `EmailRequiredWebTest` `an address that already has an account…` |
| **주소 없는 계정이 서비스를 쓰는 틈 (다른 모듈 포함)** | `EmailRequiredInterceptor` 가 허용 목록 밖 모든 경로(쓰기 때 방식에서는 변경 요청)를 403 `ACCOUNT.EMAIL_REQUIRED` 로 — board · notes · author-profile 포함. 경로는 스프링이 핸들러를 고르는 방식(디코드 후 · 세그먼트 단위)으로 읽어 `/%61pi/…` 로 우회할 수 없고, 핸들러 종류와 무관하며(함수형 라우트도), HEAD 는 GET 을 따른다. `account.http.enabled=false` 여도 켜져 있다 | `EmailRequiredInterceptorTest` · `EmailRequiredWebTest` (`a percent-encoded prefix…` · `a functional route and a HEAD call…`) · `EmailRequiredHttpDisabledWebTest` · `EmailRequiredWiringTest` · 샘플 `EmailRequiredJourneyIntegrationTest` |
| **경로 문자열로 판정하는 필터 · 인터셉터의 우회 (디코드 전 URI)** | 속도 제한 필터(`RateLimitFilter`)도 같은 결함이 있었다(`/%61pi/v1/auth/login` 은 `/api/**` 규칙에 걸리지 않아 제한을 통째로 건너뜀) — 경로를 읽는 곳은 모두 platform 의 `RequestPaths`(PathPattern · 디코드 후) 한 곳을 쓴다. `legal` 재동의 인터셉터는 핸들러 종류와 무관하게 지킨다(함수형 라우트 포함). 전수 확인 결과: 보안 체인의 `requestMatchers` 는 스프링의 경로 해석이라 안전하고, idempotency 는 경로가 아니라 `@IdempotentOperation` 애너테이션으로 판정한다 | `RateLimitPathTest` · `ReconsentWebTest` (`a functional route is guarded…` · `an encoded spelling…`) |
| **이메일로 가입해 둔 사람이 소셜로 들어와 생긴 껍데기 계정의 막다른 길** | `POST /account/discard` — 주소를 가진 적 없는 ACTIVE 계정이 `email.required=true` 에서 유예 없이 즉시 지워진다(아래 "이메일 없는 계정 버리기"). 정지 계정은 못 한다 | `DiscardUnregisteredAccountTest` · `EmailRequiredWebTest` `an unregistered account discards itself…` |
| **탈취된 세션이 심은 연락 이메일** | 주인의 복구 동작(메일함 증명 · 비밀번호 재설정 · 현재 비밀번호로 변경 · 모든 기기 로그아웃)이 연락 이메일도 비운다. 계정 이메일이 없는 계정은 연락 이메일 등록 · 변경에 제공자 재인증. 알림 메일은 "설정에서 확인하고 지울 수 있어요" | `ContactEmailRecoveryTest` |

| **연락 이메일로 계정 찾기 · 되찾기** | 계정을 찾는 조회는 `accounts.email` 만 본다. 연락 이메일로 재설정을 요청하면 아무 메일도 안 나가고, 어떤 계정 메일도 연락 이메일로 가지 않는다 | `MailRoutingTest` |
| **여러 인스턴스의 중복 정리** | 정리는 짧은 임대(`account_locks`)를 쥔 하나만 돈다. 해제는 **가져간 쪽의 소유 표가 맞을 때만** — 일 도중 만료돼 다른 인스턴스가 가져갔다면 늦게 끝난 옛 보유자의 해제가 그 임대를 풀지 못한다. 잡 큐가 있으면 주기마다 한 인스턴스만 잡을 넣는다 | `AccountErasureTest` `two overlapping purge runs…`, `AccountPurgeJobTest` `repeated ticks inside one interval…`, `AccountMaintenanceLeaseTest`, `JdbcAccountMaintenanceLeaseDbTest` (두 DB, 32 스레드 + 소유 표) |

## 닉네임 (`skeleton.account.display-name.*`)

게시판 · 댓글에 계정 id(`acc_…`) 대신 사람이 읽는 이름이 보이게 하는 부분이다. 모듈은 메커니즘만 내고, 어떤 방식을 쓸지는 앱의 `application.yml` 이 정한다 — 기본은 이 기능 이전과 같다(중복 허용 · 선택 · 자동 닉네임 없음).

| 키 | 기본 | 뜻 |
|---|---|---|
| `uniqueness` | `NONE` | `NONE` 중복 허용 · `UNIQUE` 비교용 키가 같은 닉네임은 하나만(`409 ACCOUNT.DISPLAY_NAME_TAKEN`) · `TAGGED` 중복 허용 + 서버가 4자리 꼬리표를 붙인다(`닉네임#0417`) |
| `required-on-sign-up` | `false` | `true` 면 이메일 가입 요청에 닉네임이 없을 때 `400` (필드 `displayName`, 코드 `Required`) |
| `fallback` | `NONE` | `GENERATED` 면 닉네임 없이 만들어지는 계정(소셜 · 매직 링크 · 이름 없는 가입)에 `user-1a2b3c` — 무작위 6자리 16진수. **이메일 앞부분은 쓰지 않는다**(개인정보) |
| `reserved` | `[]` | 거절할 닉네임(운영자 · 예약어 …). 대소문자 · 전각 · 띄어쓰기 · `_` 를 무시하고 비교한다. 운영자 역할 계정과 시드는 예외. **소셜 제공자가 준 이름도 걸린다**(걸리면 이름을 버리고 `fallback` 이 채운다 — 제공자 이름으로 우회하지 못한다). 기본은 꺼짐 — 사칭 방지의 최소선이다 |
| `social.import-display-name` (닉네임 표가 아니라 `skeleton.account.social.*`) | `true` | `false` 면 소셜 **가입** 때 제공자가 준 이름(Google 실명 · Kakao 닉네임 · OIDC `name`)을 버리고 이름 없는 계정으로 만든다 — `fallback: GENERATED` 면 자동 닉네임이 채우고 `NONE` 이면 이름이 없어 프런트가 「닉네임 정하기」를 받는다. 이름은 가입 때 **한 번**만 쓰인다 — 이미 연결된 아이덴티티의 재로그인과 수동 연결(`IdentityService.link`)은 제공자 이름을 계정에 쓰지 않으므로(콘솔을 고쳐도 기존 계정이 자동으로 채워지지 않는다) 이 설정은 가입 경로 하나만 바꾼다. 작성자 프로필의 첫 프로필은 계정 닉네임을 복사하므로 이름 없는 계정은 이름 없는 첫 프로필이 된다. 이메일은 이 설정과 무관하다 (`SocialDisplayNameImportTest`, `sample` 의 `GlobalSocialJourneyIntegrationTest`) |
| `change-limit` · `change-window` | `10` · `1d` | 한 계정이 창 안에 닉네임을 **바꿀 수 있는** 횟수 — 넘으면 `429 ACCOUNT.RATE_LIMITED`(`Retry-After`). 같은 값으로의 저장 · 규칙에 걸려 거절된 요청은 세지 않는다. `TAGGED` 에서 꼬리표를 마음에 들 때까지 다시 뽑는 것과 `UNIQUE` 에서 이름이 쓰였는지 훑는 것을 막는다. `0` 이면 한도 없음 |

**규칙**(`DisplayNameRules` — 가입 · 프로필 수정 · 제공자 이름 · 시드가 모두 거친다; **같은 타입의 빈을 두면 기본 규칙(`DefaultDisplayNameRules`)을 갈아 끼운다** — `clean` · `key` · `problemOf` · `sanitize`, 예약어는 설정이라 규칙을 바꿔도 그대로 걸린다): 앞뒤 공백 제거 · 연속 공백 한 칸 · 1..60 글자(코드 포인트) · 제어 문자 · 줄바꿈 · 보이지 않는 문자(Unicode Default_Ignorable_Code_Point: zero-width · 방향 제어 · 한글 채움 문자 · 점자 빈칸 · CGJ U+034F · 태그 문자 …) 거부 · `#` · `@`(전각 포함) 거부(꼬리표 · 멘션과 헷갈린다) · `deleted:` 접두 거부(탈퇴한 작성자 톰스톤과 같은 모양) · 보이는 글자가 하나도 없는 이름 거부.
**보이지 않는 글자 중 허용하는 둘**: ① **변형 선택자**(U+FE00–FE0F · U+E0100–E01EF — 이모지 `❤️`, 한자 이체자)는 보이는 글자 **바로 뒤에 하나** ② **ZWJ**(U+200D)는 **두 이모지 사이에서만**(`👩‍💻` 같은 시퀀스). 이 둘은 표시 문자열에는 **그대로 남고** 비교용 키에서는 **무시된다** — `수민` 과 `수민`+U+FE0F 가 `UNIQUE` 에서 충돌하고 `TAGGED` 에서 같은 꼬리표 줄을 쓴다. 한계: 페르시아어 ZWNJ · 인도계 문자의 ZWJ 처럼 글자 규칙에 필요한 보이지 않는 글자는 기본 규칙이 거부한다(이 기능 이전과 같다) — 그 언어를 받는 앱은 `DisplayNameRules` 빈으로 규칙을 바꾼다. 태그 문자로 쓰는 국기 하위 분할(🏴󠁧󠁢󠁥󠁮󠁧󠁿)도 거부된다. 사람이 낸 값은 **거절**(400 필드 오류)하고, 소셜 제공자 · 시드가 준 값은 **고쳐 쓴다**(가입을 막을 수 없다 — 글자를 떨구고, 맞출 수 없으면 이름 없음).
**비교용 키**(`accounts.display_name_key`): NFKC(전각 · 반각 · 호환 문자 · 결합 문자) → 소문자 → **Default_Ignorable 떨구기** → NFKC. `Ann` · `ANN` · `Ａｎｎ` 가 같다. 키릴 `а` 와 라틴 `a` 같은 **동형 문자까지 같게 보지는 않는다** — 스푸핑 방지가 목적이면 앱이 `reserved` 와 운영 모니터링을 더한다.

**저장 — 스키마는 방식과 무관하다.** `accounts` 에는 `display_name_key varchar(255)` · `display_tag char(4)` 와 유니크 `(display_name_key, display_tag)` 가 **언제나** 있고, 방식은 무엇을 저장하느냐로만 갈린다. 키는 닉네임이 있으면 방식과 무관하게 저장한다(방식을 나중에 바꿔도 다시 계산하지 않는다). 유니크 키에서 NULL 은 서로 겹치지 않는다(PostgreSQL · MySQL 둘 다 — `JdbcDisplayNameDbTest`):

| 방식 | `display_tag` | 효과 |
|---|---|---|
| `NONE` | `NULL` | 유니크에 안 걸린다 — 같은 닉네임 여럿 |
| `UNIQUE` | `'0000'` 고정 | 키 하나에 하나. `0000` 은 "꼬리표 아님" 표시라 응답의 `displayTag` 는 `null` |
| `TAGGED` | `'0001'`..`'9999'` | 키 안에서 겹치지 않는 무작위 꼬리표 |

- **언제 확인하나**: 프로필 수정(`PATCH /account/me`)과 **가입 확인을 끝낼 때**(`POST /auth/verify-email` — 계정이 만들어지는 순간). 가입 **요청**(202)은 닉네임이 쓰였는지 알리지 않는다 — 닉네임이 쓰였는지는 게시판에 보이는 공개 정보라 확인 시점의 `409` 는 괜찮지만, 계정 존재 여부는 여전히 어느 응답에도 드러나지 않는다. 필수 · 형식 검사(`400`)는 요청 본문만 보고, 주소가 있든 없든 **같은 시점 · 같은 응답**이다 (`DisplayNameFlowsTest` · `DisplayNameWebTest`). 확인 때의 `409 DISPLAY_NAME_TAKEN` 은 **시도를 소비하지 않는다**(코드는 맞았다): 같은 `signUpId` · 같은 코드로 `POST /auth/verify-email` 의 선택 필드 **`displayName`** 에 다른 닉네임을 실어 다시 확인한다(가입 요청의 닉네임보다 우선 · 같은 규칙으로 `400` 검사 — 규칙에 걸려도 시도는 그대로). 시도가 닫히지 않으니 코드 추측 횟수(`attempts_left`)도 맞는 코드가 쓴 한 번을 돌려받는다 — **무차별 대입 계산은 그대로다**: 이 경로에 오는 사람은 이미 맞는 코드를 아는 사람이라 더 추측할 것이 없고, 틀린 코드는 지금까지처럼 시도를 깎으며, 주소별 · IP별 추측 한도(`guess:email` · `verify:ip`)는 호출마다 센다(닉네임을 바꿔 다시 시도하는 정상 사용도 호출 수는 센다). (`DisplayNameFlowsTest` `UNIQUE - a clash at verification keeps the attempt alive…` · `DisplayNameWebTest` `verify accepts an optional displayName…`)
- **TAGGED**: 새 닉네임마다 키 안에서 무작위 꼬리표를 뽑는다. 겹치면(경합 포함 — 먼저 읽고 쓰지 않고 유니크 제약이 가른다) 다시 뽑고, 몇 번 겹치면 키 안의 빈 꼬리표를 직접 찾고(`displayTagsOf`), 9999 개가 다 차면 `409 DISPLAY_NAME_TAKEN`. 닉네임을 **바꾸면** 꼬리표를 새로 뽑는다 — 키가 그대로(대소문자 · 전각만 바뀜)면 지킨다.
- **탈퇴와 닉네임**: 유예 중(`DELETED`)에는 키 · 꼬리표가 **잡혀 있다**(주인이 돌아올 수 있다). 지워지면(`ERASED`) 이름 · 키 · 꼬리표가 모두 NULL 이 되어 풀린다. `ERASED` 행에는 닉네임을 쓰지 않는다(저장소가 거절).
- **작성자 이름 조회**: board 같은 모듈은 이 모듈을 모른 채 platform 의 `AuthorDirectory` 로 이름을 묻는다. 기본 구현(`AccountAuthorDirectory`, `@ConditionalOnMissingBean`)은 닉네임 · 꼬리표를 `where id in (…)` **한 번**으로 준다. 상태별: `ACTIVE` · 유예 중(`DELETED`) · `SUSPENDED` 는 행이 살아 있으니 **이름 그대로**(유예 중인 주인은 돌아올 수 있고, 정지된 계정의 글을 누가 썼는지 숨기면 운영자도 읽기 어렵다), `ERASED` 는 이름 없음(board 쪽은 이미 톰스톤). 돌판(팬덤)마다 다른 닉네임처럼 범위별 이름이 필요한 앱은 같은 타입의 빈을 두면 기본 구현이 물러난다 — `AuthorContext(source="board", scope=<게시판 코드>)` 로 어느 게시판에서 묻는지 알 수 있다.

**방식을 바꿀 때 (이미 계정이 있는 DB)** — 아무것도 안 하면 안전하지만 옛 계정은 새 방식의 보호를 못 받는다. 먼저 중복을 본다: `select display_name_key, count(*) from accounts where display_name_key is not null group by 1 having count(*) > 1`.

| 바꿈 | 아무것도 안 하면 | 옛 계정까지 적용하려면 |
|---|---|---|
| `NONE` → `UNIQUE` | 옛 닉네임은 `display_tag` 가 NULL 이라 유니크에 안 잡힌다 — 새 사람이 옛 사람의 이름을 쓸 수 있다 | 중복을 먼저 고친(운영자가 이름을 바꾼) 뒤 `update accounts set display_tag = '0000' where display_name_key is not null and display_tag is null` — 중복이 남아 있으면 유니크 위반으로 **실패**한다(그것이 점검이다) |
| `NONE` → `TAGGED` | 옛 계정은 꼬리표 없이 보이고 새 계정만 꼬리표를 받는다. 충돌은 없다 (**백필은 방식을 `TAGGED` 로 바꾸기 _전에_ 돌린다** — 바꾼 뒤에 돌리면 그 사이 새 계정이 무작위로 뽑은 꼬리표(`0001` …)와 백필이 매기는 `0001`.. 이 부딪쳐 백필이 유니크 위반으로 중간에 실패한다. 백필이 끝날 때까지 가입 · 닉네임 변경이 없는 때(점검 창)에 한다) | PostgreSQL: `update accounts a set display_tag = lpad(r.n::text, 4, '0') from (select id, row_number() over (partition by display_name_key order by created_at, id) n from accounts where display_name_key is not null and display_tag is null) r where a.id = r.id` · MySQL 8: `update accounts a join (…같은 select…) r on r.id = a.id set a.display_tag = lpad(r.n, 4, '0')` (같은 키가 9999 를 넘으면 안 된다) |
| `UNIQUE` → `TAGGED` | `'0000'` 인 옛 계정은 꼬리표 없이 보이고 새 계정은 꼬리표를 받는다 | 위 `TAGGED` 백필을 `display_tag = '0000'` 에 대해 |
| `TAGGED` → `UNIQUE` / `NONE` | 이미 꼬리표를 가진 계정은 꼬리표가 계속 보인다. `UNIQUE` 는 옛 `닉네임#0417` 과 새 `닉네임` 을 같은 이름으로 보지 않는다 | `TAGGED` → `UNIQUE` 는 겹치는 이름을 먼저 정리한 뒤 `update accounts set display_tag = '0000' where display_name_key is not null`. 꼬리표를 걷어내려면 `update accounts set display_tag = null` (`NONE`) |

**닉네임 열이 없던 DB**(이 기능 이전의 `accounts` 마이그레이션을 이미 적용한 DB — 파일을 제자리에서 고쳤다. **앞으로 스키마가 바뀌면 기존 마이그레이션을 고치지 않고 새 `V<타임스탬프>__…` 파일을 더한다** — 주인의 로컬 DB 를 날리지 않는다): `flyway repair` 로는 열이 생기지 않는다. `alter table accounts add column display_name_key varchar(255), add column display_tag char(4), add constraint uq_accounts_display_name unique (display_name_key, display_tag)`(MySQL 은 키 열을 `character set utf8mb4 collate utf8mb4_bin` 으로) 를 직접 돌리고, 이름이 있는 계정의 키를 채운다. 코드의 키 규칙(NFKC → 소문자 → NFKC)에 **가장 가깝게**:

```sql
-- PostgreSQL (13+): normalize() 가 있다 — 전각 · 호환 문자도 코드와 같은 키가 된다
update accounts set display_name_key = lower(normalize(display_name, NFKC)) where display_name is not null;
-- MySQL 8: NFKC 가 없다 — 소문자 키까지만. 전각 · 호환 문자가 든 이름은 코드가 만드는 키와 달라 UNIQUE 비교에서 빠진다 (그 계정이 이름을 바꾸면 바로잡힌다)
update accounts set display_name_key = lower(display_name) where display_name is not null;
```

두 쿼리 모두 **보이지 않는 글자를 떨구지 않는다**(코드의 키는 Default_Ignorable 도 떨군다) — 옛 이름에 그런 글자가 있으면 키가 코드와 달라 그 계정은 `UNIQUE` 비교에서 빠진다(그 계정이 이름을 바꾸면 바로잡힌다).
**옛 이름이 새 규칙에 맞는지 점검한다** — 규칙(`#` · `@` 거부 · `deleted:` 접두 · 보이지 않는 글자)이 생기기 전의 이름은 그대로 남아 있다(읽을 때는 문제가 없고, 이름을 바꿀 때부터 규칙이 걸린다). `#` · `@` 는 `TAGGED` 의 `닉네임#0417` 표시와 멘션을 헷갈리게 하므로 점검한다:

```sql
-- PostgreSQL: 전각 ＃ ＠ 도 NFKC 로 같은 글자
select id, display_name from accounts where normalize(display_name, NFKC) ~ '[#@]' or lower(normalize(display_name, NFKC)) like 'deleted:%';
-- MySQL: 전각은 직접 넣어 본다
select id, display_name from accounts where display_name regexp '[#@＃＠]' or lower(display_name) like 'deleted:%';
```

나온 계정은 운영자가 이름을 고치게 하거나(`TAGGED` 가 아니면 그대로 둬도 동작한다) `update accounts set display_name = replace(replace(display_name, '#', ''), '@', ''), display_name_key = … where id = …` 로 정리한 뒤 키도 다시 채운다. 새로 만든 DB 와 stamped 프로젝트의 새 마이그레이션에는 필요 없다.

**위협 · 개인정보**: 닉네임은 공개 정보다(게시판에 보인다) — `UNIQUE` 의 `409` 와 응답의 이름은 비밀이 아니다. 계정이 있는지는 닉네임으로도 알 수 없다(요청 단계는 같은 202). 자동 닉네임은 이메일 · 계정 id · 시각에서 만들지 않는다(무작위). 탈퇴한 계정의 이름 · 키 · 꼬리표는 지워진다 — `AccountJourneyIntegrationTest` 의 전체 표 스캔(`display_name_key` 의 소문자 값 포함)이 본다.

## 작성자 프로필 (`author-profile`)

한 계정이 글 · 댓글마다 다른 이름을 골라 쓰게 하는 선택 모듈이다 — 두 프로필이 같은 사람이라는 사실이 다른 사용자에게 드러나지 않는다. `account` 위에 얹히고(스키마 · `account` 코드 변경 없이 닉네임 규칙 · 예약어 · 겹침 방식 · 이름 바꾸기 한도 숫자를 그대로 따른다), 계정의 닉네임(`display_name`)은 본인 · 관리자 · 메일 인사말용 사적 라벨로 남는다. 모듈 페이지: `docs/modules/author-profile.md` (HTTP `/api/v1/author-profiles` · 설정 · 탈퇴 · 넘겨받기 · 알려진 한계 · 누출 스캔 시험과 변이 확인 기록), 프론트가 읽는 계약: `docs/author-profile-http-contract.md`(이 모듈을 고른 프로젝트에만 남는다), 설계 · 위협 모델: `docs/superpowers/specs/2026-10-07-author-profiles-design.md`.

- **계정 쪽에서 바뀌는 것은 하나뿐이다**: `AccountEventType.AUTHOR_LINK_VIEWED`(관리자가 프로필 ↔ 계정 연결을 조회했다 — `author-profile.admin.enabled` 일 때만, 대상 계정 + `by`, 프로필 id 는 싣지 않는다). 마이그레이션 · `GET`/`PATCH /account/me` · 가입 · 로그인은 그대로다. 끼우지 않은 앱(`apps/api`)은 한 줄도 다르지 않다.
- **계정 닉네임(`display_name`)은 사적 라벨이 된다**: 첫 프로필이 만들어질 때 계정 이름 · 꼬리표를 **한 번 복사**하고 그 뒤로는 따로 간다. 게시판에 보이는 이름은 프로필의 것이고, `PATCH /account/me` 로 계정 이름을 바꿔도 프로필은 바뀌지 않는다. 닉네임 규칙 · 예약어 · 겹침 방식(`UNIQUE`/`TAGGED`) · 이름 바꾸기 한도 숫자는 이 문서 「닉네임」 의 것을 그대로 쓴다(프로필 이름 바꾸기 카운터는 계정 닉네임 변경과 따로, 모든 프로필 합산). 한도(만들기 · 개수 상한)는 모두 **계정 기준**이다 — 프로필을 바꿔 한도를 피할 수 없다.
- **계정 탈퇴**: 지우기의 고리가 그 계정의 프로필 행을 모두 지우고, 글 · 댓글은 board 의 고리가 `author_id` 만 톰스톤으로 바꾼다(`author_ref` 는 그대로) — 두 프로필의 글이 **같은 순간** "삭제된 사용자"가 된다(스펙 §4 19, 수용한 잔여 위험). 정지 · 유예 중에는 공개 표시가 없다.
- **켜는 시점 권고(Ovation 등 새로 시작하는 앱)**: 첫 배포부터 켠다. 켜기 전에 쓴 글은 계정 id 를 공개 작성자 id 로 내보낸 적이 있어 **첫 프로필과 영영 이어진다**(켠 뒤 만든 프로필은 이어지지 않는다). 비밀이 필요한 활동은 새 프로필로 하라고 안내하는 것은 앱의 몫이다. 끄는 것(모듈 빼기)은 지원하는 방향이 아니다(스펙 §11).
- **연결 조회(`admin.enabled`)의 한계**: 대상 계정이 지워지면 그 계정의 감사 행에서 `by` 가 scrub 으로 사라진다 · 같은 `traceId` 로 요청 줄(프로필 id)과 감사 줄(대상 계정)을 이으면 조회한 프로필과 계정이 이어진다(로그는 신뢰 영역) · 기록 실패는 경고 로그만 남기고 조회는 나간다. 계약 · 전제(`account.audit.enabled`)는 모듈 페이지.

## 로그인 아이디 (`skeleton.account.login-id.*`, 기본 꺼짐)

이메일이 유일한 간판이면 이메일을 바꿀 때마다 "내 계정이 뭐였지"가 흔들린다. 그래서 **바뀌지 않는 간판**을 하나 둔다: 로그인 아이디. 켜면 **전원이 갖는다**(이메일 가입은 가입 양식에서, 소셜 · 매직 링크 · 켜기 전부터 있던 계정은 로그인 직후 게이트에서). 아이디는 **비공개**다 — 본인과 운영자만 본다. 한 번 정하면 **못 바꾼다**(본인도 운영자도 바꾸는 길이 없다). 꺼져 있으면(기본) API · 응답 · 로그인이 이 기능 이전과 같다.

```yaml
skeleton:
  account:
    login-id:
      enabled: true
      reserved: [admin, root, support]   # 내줄 수 없는 아이디(기호 · 대소문자 무시). 시드 계정은 예외
```

설정 전체: [docs/config/modules/account.yml](config/modules/account.yml). 반쪽 스위치는 없다(가입만 · 로그인만 켜는 조합은 "전원이 아이디를 가진다"와 어긋난다). 꺼져 있으면 새 경로 셋(`POST /account/login-id`, `/availability`, `/forgot`)은 **등록되지 않는다** — 익명 호출은 어떤 모르는 경로와도 같은 `401`(공개 목록에 없다), 로그인한 호출은 `404` 다([계약 문서](account-http-contract.md) "With the feature off", `LoginIdDisabledWebTest`). 켠 앱은 보호 환경에서 `<P>_ACCOUNT_BLOCKS_SECRET` 이 **필요하다**(아래 "재사용 금지").

### 규칙 (`LoginIds` 한 곳)

- 허용 문자는 영문 `a-z` · 숫자 · `.` `-` `_`, 길이 4~20(`min-length` · `max-length`, 열 길이 30을 넘길 수 없다). 입력은 대문자도 받고 **ASCII 영문만** 소문자로 접어 저장 · 비교한다(앞뒤 공백은 뗀다). 켈빈 기호(`K`) · 전각 · 한글은 접지 않고 거절한다.
- 기호(`.` `-` `_`)는 맨 앞 · 맨 뒤 · 연달아 올 수 없고, 영문이 하나는 있어야 한다(숫자만 · 전화번호 · 생일이 그대로 아이디가 되는 것을 막는다). `@` · 공백은 없다 — 그래서 로그인 칸에서 **`@` 가 있으면 이메일, 없으면 아이디**로 가른다.
- 거절은 `400 COMMON.VALIDATION_FAILED` + `errors[].field="loginId"` + `Required` · `Size` · `Pattern`. **30자(열 길이)를 넘으면 모양과 상관없이 `Size`**(정규식을 긴 입력에 돌리지 않는다 — 6KB 입력이 스택을 터뜨린 적이 있다, 시험 `LoginIdRulesTest` `an input far longer than the column…`). 문제가 겹치면 모양 → 길이 순이다(`12` 는 `Pattern`, `a` 는 `Size`).
- 예약어는 형식 오류가 아니라 **"이미 쓰는 아이디"와 같은 답**(`409 ACCOUNT.LOGIN_ID_TAKEN`)이다 — 저장소를 읽기 전에 판정한다. 이 설정은 닉네임의 `display-name.reserved` 와 **따로**다(그 목록은 공개 이름의 사칭을 막는 것이고 아이디는 공개되지 않는다).
- 교체 가능한 규칙 빈은 없다 — 규칙이 로그인 칸의 `@` 판별 · 열 길이와 묶여 있어 앱이 바꾸면 깨진다. 설정으로 바뀌는 것은 길이와 예약어뿐.
- 시험: `LoginIdRulesTest`.

### 재사용 금지 — 원문은 지우고 해시만 남긴다

- 살아 있는 아이디는 `accounts.login_id` 의 유니크가, **한 번이라도 쓰인 아이디**는 전용 표 `account_used_login_ids`(`hash` 기본키 · `used_at`)가 지킨다. 아이디를 **정하는 순간**(가입 확정 · 게이트) 같은 트랜잭션에서 해시를 넣으므로 계정이 어느 길로 사라져도(탈퇴 · 운영자 지우기 · 행 삭제 · 버리기) 그 아이디는 다시 못 쓴다. 탈퇴 익명화는 `login_id` 열만 비운다.
- 해시는 `blocks.secret` 으로 키를 준 HMAC-SHA256(`login-id:` 접두사)이다. 열쇠 없는 해시는 쓰지 않는다 — 아이디는 짧아서 사전 대입으로 되돌려지고, 그러면 원문을 둔 것과 같다. 표에는 **계정 id 도 없다**: `used_at` 은 UTC **날짜로 잘려** 시각이 남지 않는다 — 가입 길의 값은 계정 행의 `created_at` 과 같은 날짜라 **하루 단위로만 겹칠 뿐**, 해시만으로 지워진 계정을 특정하지는 못한다(`JdbcLoginIdDbTest` `used_at is the UTC midnight…`).
- **비밀이 바뀌면** 탈퇴한 아이디의 해시가 맞지 않아 재사용 금지가 조용히 풀린다(살아 있는 아이디는 열의 유니크가 계속 지킨다). 그래서 보호 환경에서 `login-id.enabled=true` 인데 `blocks.secret` 이 비면 `AccountDeployGuard` 가 **기동을 막는다**(관리자 API 만 켠 앱은 지금처럼 경고만). 있던 비밀을 다른 값으로 바꾸는 것까지는 막지 못한다 — 처음부터 따로 두고 바꾸지 않는다.
- 시험: `JdbcLoginIdDbTest`(두 DB — `erasing empties the column but the id stays taken…` · `the DELETE mode purge…` · `an operator erase or purge…`), `InMemoryLoginIdRepositoryTest`, `AccountDeployGuardTest` `login ids on in a protected env without a blocks secret…`.
- ⚠ **개인정보 처리방침에 적을 것(앱의 몫)**: 탈퇴 뒤에도 아이디의 **되돌릴 수 없는 해시**가 재사용을 막으려고 기한 없이 남는다(아래 "삭제 수명주기" 목록의 ④).

### 가입 · 실시간 중복 확인

- 가입 요청의 `loginId`(필수)는 **본문만 보는 형식 검사**가 IP 한도 앞이고, 쓸 수 있는지(예약어 · 살아 있는 · 탈퇴한 아이디)는 캡차 뒤 · 비밀번호 정책 앞이다. 그 판정은 **아이디만** 본다 — 같이 낸 이메일이 가입돼 있든 아니든 같은 시점 · 같은 응답이라 이메일 존재는 여전히 드러나지 않는다. 요청 스레드가 읽는 것은 아이디 조회뿐이다. 아이디는 가입 시도에 실려 있다가(메일 · 이벤트 · 로그에는 없다) **계정이 만들어지는 순간**에만 잡힌다 — 인증 대기 중의 선점(예약)은 하지 않는다(메일함을 증명하지 않은 사람이 주소만 바꿔 가며 아이디를 잠가 둘 수 있다).
- 확정(`verify-email`)에서 그 사이 남이 가져갔으면 `409 ACCOUNT.LOGIN_ID_TAKEN` 이고 **시도는 닫히지 않는다**(닉네임 겹침과 같이) — 같은 `signUpId` · 같은 코드에 다른 `loginId` 를 실어 다시 낸다. 갈아 끼운 아이디가 예약어여도 같은 `409` + 시도 유지이고, 갈아 끼운 아이디의 형식 오류는 코드 확인보다 앞선 `400` 이다.
- 실시간 확인 `POST /account/login-id/availability`(공개)는 늘 `200` `{ normalized, available, reason?, code? }` — **`reason` · `code` 는 해당 없을 때 키 자체가 없다**(null 을 싣지 않는다). 형식 오류(`INVALID`)는 규칙이 공개된 것이라 구분해 주고 계정 저장소를 읽지 않으며 **한도를 쓰지 않는다**. `TAKEN` 은 예약어 · 살아 있는 · 탈퇴한 아이디를 **하나로** 답하고, 판정은 이유와 무관하게 **같은 조회**를 한다(응답 시간으로 가르지 못하게). POST 인 이유는 후보 아이디가 주소 줄 · 접근 로그에 남지 않게. 응답은 `Cache-Control: no-store`. 한도는 한 IP 가 10분에 30번(`check-per-ip` · `check-per-ip-window`)이고, 넘어도 가입이 막히지는 않는다(제출 때의 `409` 가 받는다).
- 시험: `LoginIdSignUpTest`, `LoginIdAvailabilityTest`, `LoginIdWebTest`, `SignUpFormatBeforeLimitTest`.

### 로그인 — 입력 방식마다 한도 버킷이 따로다

`POST /auth/login` 의 본문은 그대로(`accountId | username | email` 중 하나 + `password`)이고 `auth` 모듈은 고치지 않는다. `username` 에 `@` 가 없으면 아이디로 읽는다(`AccountAuthRepository.findBy`; 저장된 아이디가 정규화한 입력과 글자 그대로 같을 때만). 실패 응답은 없는 아이디 · 틀린 비밀번호 · 비밀번호 없는(소셜만) 계정 · 탈퇴한 아이디가 **글자까지 같은** `401` 이다(`LoginIdWebTest` `L5 - …`). 토큰 · `GET /auth/me` 에는 아이디가 없다.

한도 버킷은 **입력 방식마다 따로**다: `email` 은 그 주소, `username`(아이디로 읽힌 것)은 **`loginId:<정규화한 입력>`**(계정이 있든 없든, 저장소를 읽지 않는다), `accountId` 는 그 계정의 주소 버킷. 아이디를 계정의 이메일 버킷으로 합치지 않는 까닭: 합치면 이메일을 아는 사람이 "이메일 버킷을 채운 뒤 후보 아이디를 보내 `429` 가 나는지"로 **그 계정의 아이디를 맞혀 볼 수 있다**(후보 하나에 요청 하나). **대가**: 한 계정의 비밀번호 추측이 창마다 이메일 몫 + 아이디 몫이다(`login.per-account` 의 두 배). 시험: `LoginControlsTest` `a login id has its own bucket…` · `with the email bucket full a known id and an unknown id are answered the same…`.
로그인 실패 · 잠김 이벤트의 `detail.id` 는 아이디 입력이면 **서버 비밀의 HMAC**(사전으로 되돌려지지 않는다), 이메일 · `accountId` 입력은 기존 그대로다.
한도 저장소의 키는 다르다: `AccountRateLimits.digest`(`Abuse.kt`)가 `loginId:<정규화한 입력>` 을 **열쇠 없는 SHA-256**(앞 24자)으로 줄여 쓰므로, Redis 한도 저장소(`redis-rate-limit`)를 쓰면 그 키가 창 길이만큼 저장소에 남고 아이디는 짧아 사전으로 되돌려질 수 있다. 저장소를 읽는 사람에게는 아이디를 숨기지 않는다는 기존 선(스펙 §18.1 의 14)과 같은 취급이라 코드는 그대로 둔다.

### 아이디 게이트 — 정하지 않은 계정은 먼저 정한다

소셜 첫 로그인 · 매직 링크로 만들어진 계정 · 아이디 없는 시드 · 기능을 켜기 전부터 있던 계정(백필은 없다 — 다음 로그인 때 게이트).

- `LoginIdRequiredInterceptor`(순서 `-110`)가 아이디 없는 로그인 계정에게 **허용 목록 밖 모든 `/api/**`**(읽기 포함)를 `403 ACCOUNT.LOGIN_ID_REQUIRED` 로 막는다. 경로 읽기 · 핸들러 종류 · HEAD · 익명 · preflight 는 이메일 게이트와 같다(`EmailRequiredInterceptor` 와 같은 해석기 `GatePathRule`). `skeleton.account.http.enabled=false` 여도 `login-id.enabled` 만 보고 등록된다.
- **순서는 아이디(`-110`) → 이메일(`-100`) → 약관 재동의(`0`)** 이고 이메일 방식이 무엇이든 같다. 먼저 도는 쪽이 읽은 계정 한 줄을 요청 속성에 두고 뒤가 받아 쓴다(요청마다 한 번만 읽는다).
- **허용 목록은 두 게이트가 하나를 같이 쓴다**: 이메일 게이트의 고정 목록(`GET /account/me` · `email/change`(+`/confirm`) · `discard` · `delete`(+`/delete/confirmation`) · `/auth/**` · `/legal/**` · `/docs/**`) + 아이디가 켜져 있으면 `POST /account/login-id` · `POST /account/login-id/availability`. 설정은 더하기만 하고, **`extra-exclude-paths` 는 게이트별**이다 — `email.extra-exclude-paths` 가 아이디 게이트를 열지 않고 `login-id.extra-exclude-paths` 가 이메일 게이트를 열지 않는다(둘 다 읽지 못하면 기동 실패). 아이디가 있는데 주소가 없는 계정은 이 게이트를 지난다.
- **정하기** `POST /account/login-id {loginId}` → `200`(`me` 와 같은 본문). **이메일이 없어도 된다**(아이디만 먼저). 재인증은 없다 — 한 번도 없던 값을 처음 정하는 온보딩이고 아이디만으로는 로그인되지 않는다. `409 LOGIN_ID_TAKEN`(예약어 · 살아 있는 · 탈퇴한 아이디 모두) · `409 LOGIN_ID_ALREADY_SET` · `403 NOT_ACTIVE` · `429 RATE_LIMITED`. 이벤트 `LOGIN_ID_SET`(`via` 만, 값은 싣지 않는다).
- **한도(`claim-limit` 10 / `claim-window` 1h)는 계정별 + IP 별**(`login-id-claim:ip`, 값은 공유하고 버킷만 따로)이고 **형식을 통과한 시도만**(성공 · 실패 모두) 센다. 이유: 소셜 신원 하나로 「정하기 → 버리기(`discard`) → 다시 소셜 로그인」을 되풀이하면 계정마다 한도가 새로 열려 **아이디를 영구히 태울 수 있다**(탈퇴해도 해시가 남으므로). 대가: 한 IP 뒤의 사용자들이 이 한도를 같이 쓴다(공유기 · 회사 망). `check-per-ip-window` · `claim-window` 는 양수여야 기동한다.
- **빠져나가는 길**: 정한다 / 로그아웃(`/auth/**` 가 열려 있다) / `discard`(이메일 필수 앱의 주소 없는 계정 — 아이디를 정하기 **전에** 나갈 수 있어야 아이디 하나를 헛되이 태우지 않는다) / 계정 삭제. 아이디 **없는** 계정이 로그인한 채 `login-id/forgot` 을 부르면 허용 목록에 없어 아이디 게이트의 403 이다(`password/forgot` 과 같다).
- 시험: `LoginIdRequiredInterceptorTest` · `LoginIdRequiredWebTest` · `LoginIdRequiredWiringTest` · `LoginIdClaimTest`, `LoginIdDisabledWebTest`(꺼짐: 게이트가 등록되지 않는다).

### 아이디 찾기 (`POST /account/login-id/forgot`)

공개. 본문 `{ email, captchaToken? }` → **늘 `202`**. `password/forgot` 과 한 글자씩 같은 틀이다: 요청 스레드는 IP 한도(`login-id-forgot:ip`, 값은 `reset.per-ip`)와 캡차만 보고, 주소 조회 · 메일은 뒤에서 한다 — 응답과 응답 시점이 계정 존재와 무관하다. 주소별 한도(`login-id-forgot:email`, `reset.per-email`)는 조용히 넘긴다. 메일은 **계정 이메일로만** 간다(연락 이메일로는 가지도 않고 그것으로 계정을 찾지도 않는다 — `MailRoutingTest`), `ACTIVE` · `PENDING_VERIFICATION` 계정에게만.

- 메일 종류는 `MailKind.LOGIN_ID_REMINDER` **하나**이고 `vars["loginId"]` 의 유무로 두 본문이다: 아이디가 있으면 아이디 한 줄 + 로그인 페이지 링크(토큰 없음), 아직 안 정했으면 "아직 아이디를 정하지 않았어요. 로그인하면 정할 수 있어요" + 같은 링크. **제목에는 아이디가 없다.** 주소 없는 계정(쓰기 때 방식에서 이메일을 안 등록한 소셜 계정)은 이 길을 쓸 수 없다 — 아이디는 설정 화면에서 본다.
- ⚠ **`AccountMailTemplates` 를 바꿔 끼운 앱은 `vars["loginId"]` 가 없는 경우도 처리해야 한다**(없으면 "아직 안 정했다" 본문).
- 아이디가 메일에 나오는 곳은 이 한 통뿐이다. **비밀번호 재설정 메일 · 기존 메일은 한 글자도 바뀌지 않았다**(골든 파일 무변). 로컬 편의 `LogOnlyMailTransport(showLinks=true)`(메일 모듈이 없을 때 `mail.log-links` 로 켠다)는 본문 전체를 로그에 찍으므로 **아이디도 찍힌다** — 코드 · 링크와 같은 취급이라 숨김 기본값에서만 시험으로 고정돼 있고(`MailPathLogLeakTest`) 보호 환경에서는 켜면 기동 실패다.
- 시험: `LoginIdReminderTest`, `AccountMailGoldenTest`(`login_id_reminder*` 골든), `MailRoutingTest`.

### 아이디가 나가는 곳은 셋뿐 — 비공개의 세 겹 시험

1. `GET/PATCH /account/me` · `POST /account/login-id` 응답의 `loginId`(본인), 2. 계정 이메일로 가는 아이디 찾기 메일(본인의 메일함), 3. `GET /admin/accounts` · `/{id}` 의 `loginId`(`ADMIN` — 켠 앱에서만 키가 있다)와 `?loginId=` **정확히 일치**(정규화 뒤) 검색. 꺼진 앱은 쿼리를 읽지 않는다. 조각 검색은 없다(훑어보기 방지). `MODERATOR` 는 못 본다(매 호출 역할을 다시 읽는 기존 규칙).

그 밖에는 싣지 않는다 — 토큰 · `GET /auth/me`, 게시판 · 작성자 프로필 · 알림 · 첨부, `AccountName`, 이벤트 · 감사 행의 detail, 로그(요청 DTO 의 `toString` 을 가린다). 세 겹으로 고정: ① 응답 전수 스캔 — `apps/sample` `LoginIdLeakIntegrationTest`(OpenAPI 문서의 GET 전부를 다른 사용자 · 익명 · `MODERATOR` 로 훑는다) ② 응답 타입 가드 — 같은 시험의 `only MeView, AdminAccountResponse and AuthMethodsView have a loginId property…` ③ DB 전수 스캔 — `JdbcErasureDbTest`(탈퇴 익명화 뒤 어디에도 없다)와 `JdbcLoginIdDbTest`.

### 남는 위험 (설계가 막지 못하는 것)

- **아이디가 "있는지"는 누구나 한 번에 확인한다.** 실시간 확인은 로그인도 캡차도 없이 답한다 — 사용자가 다른 곳에서 쓰는 핸들을 아이디로 정하면 그 핸들을 아는 사람이 "이 서비스에 가입했다"를 추정할 수 있다(민감한 서비스에서는 실제 위험). 훑어서 목록을 만드는 것은 한 IP 하루 4,320번으로 느려질 뿐이다. 로그인한 계정도 「정하기」의 `409` 로 알 수 있다(계정당 · IP당 시간당 10번).
- 넓힌 규칙은 닮은 아이디를 만든다(`a.b` · `a-b` · `a_b` 는 서로 다른 사람). 사칭은 아니지만 본인이 헷갈린다 — 아이디 찾기가 출구다. 규칙은 나중에 넓힐 수는 있어도 좁힐 수는 없다.
- 서버 비밀이 바뀌면 탈퇴한 아이디의 재사용 금지가 풀린다(위). 못 바꾼다는 것 자체(오타 · 후회의 길은 탈퇴 뒤 새 계정뿐, 옛 아이디는 영영 묶인다).
- 아이디를 아는 사람은 그 계정의 **아이디 입력 비밀번호 로그인을 잠깐 막을 수 있다**(10분에 10번 한도를 대신 쓴다) — 이메일에 이미 있는 대가이고 소셜 · 매직 링크 로그인은 영향받지 않는다. 비밀번호가 아이디와 같아도 막지 않는다.
- 아이디가 없는 계정에 붙은 탈취된 세션은 주인 대신 아이디를 정해 버릴 수 있다(재인증이 없다) — 기능을 켜는 순간의 기존 계정에만 해당한다.

## 이메일 필수 (`skeleton.account.email.*`)

소셜 제공자가 권위 있게 확인한 주소(Gmail 등 — `emailAuthoritative`)는 가입 때 **자동 등록**된다(지금 동작). Kakao · Naver · X · LINE · 비-Gmail Google · 이메일 동의를 안 한 가입은 **주소 없는 계정**으로 만들어진다. 모듈 기본(`email.required: false`)은 그 계정도 서비스를 그대로 쓴다. 앱이 `email.required: true` 로 켜면:

- **주소 없는 계정은 허용 목록 밖의 모든 API 에서 `403 ACCOUNT.EMAIL_REQUIRED`** 다 — 다른 모듈(board · notes · author-profile · storage …)의 엔드포인트도. 모듈끼리 서로를 모르므로 **경로**로 지킨다: `account` 가 등록하는 MVC 인터셉터 `EmailRequiredInterceptor`(`legal` 의 재동의 인터셉터와 같은 방식이고, 순서는 이쪽이 먼저 — 아이디(켰을 때, 위) → 주소 → 약관 동의). 계정 HTTP(`skeleton.account.http.enabled=false`)를 꺼도 인터셉터는 `required` 만 보고 등록된다 — `me.emailRequired` · 재인증 면제는 컨트롤러와 무관하게 `required` 를 따르므로 403 도 같이 있어야 "필수" 가 말뿐이 아니다. 익명 요청은 건드리지 않는다(401). 계정 한 줄 읽기.
- **허용 목록**("로그인 직후" 방식의 목록 — 코드에 **고정** — 설정은 `email.extra-exclude-paths` 로 **더하기만** 한다. 목록을 통째로 바꿔 주소 없는 계정을 가두는 길은 없고, `METHOD /패턴` 을 읽지 못하면 조용히 무시되는 대신 **기동 실패**): `GET /api/v1/account/me`(상태 판별) · `POST /api/v1/account/email/change` · `POST /api/v1/account/email/change/confirm`(등록 · 확인 · 재전송은 같은 `email/change` 를 다시 부른다) · `POST /api/v1/account/discard`(껍데기 계정 버리기) · `POST /api/v1/account/delete` · `…/delete/confirmation`(계정 삭제) · `/api/v1/auth/**`(로그인 · 로그아웃 · 갱신 · 세션 · `methods` · `auth/me`) · `/api/v1/legal/**`(약관 조회 · 동의) · `/api/v1/docs/**`. 헬스(`/health`)는 `/api/**` 밖이라 막히지 않는다. 막는 범위(`/api/` 아래 전부)는 고정이다. 정적 자원 핸들러(없는 경로는 404 그대로) · 에러 디스패치 · CORS preflight 는 막지 않는다.
- 상태 판별: `GET /account/me` 의 **`emailRequired`**(켠 앱에서만 키가 있다: 주소가 없으면 `true`, 있으면 `false`; 꺼져 있으면 키 자체가 없다). 403 본문의 `code` 로도 알 수 있다. 아래 "이메일을 받는 시점"의 **쓰기 때** 방식에서는 `emailRequired` 가 늘 `false` 이고 `emailRequiredToWrite` 를 본다.
- **첫 이메일 등록은 재인증이 없다.** 주소가 한 번도 없던 계정이 첫 주소를 넣는 것은 권한 상승이 아니라 온보딩이다 — 방금 소셜로 로그인한 사람에게 제공자 동의를 또 시키지 않는다. 같은 `POST /account/email/change`(+ `/confirm`)를 쓰고 코드(6자리 · 10분 · 5번 · 요청한 세션에서만 · 응답에 `expiresAt` / `resendAvailableAt`)로 주소 소유를 증명한다. 확인되면 `email_verified=true`.
- 그 주소가 **이미 다른 계정의 이메일**이면 응답은 같은 202 이고 코드는 가지 않는다 — 대신 그 주소의 주인에게 **"이 주소로 이메일 등록을 시도했어요" 메일**(`EMAIL_REGISTRATION_ATTEMPT`, 토큰 없는 링크: 로그인 페이지 · 가입 수단 · "이미 이 주소의 계정이 있어요. 그 계정으로 로그인한 뒤 설정에서 소셜 로그인을 연결해 주세요")이 간다. 가입의 "가입을 시도한 분이 있어요" 문구는 이 상황과 맞지 않아 따로 둔다. 화면은 코드 입력에서 멈추므로 프론트가 "코드가 오지 않으면 그 주소로 이미 계정이 있을 수 있어요 — 그 계정으로 로그인해 소셜을 연결하세요" 를 함께 보여 준다. 그 절차에서 연결이 `IDENTITY_TAKEN` 으로 막히면(소셜이 이 껍데기 계정에 붙어 있다) **먼저 껍데기를 버린다**(아래). 운영자가 지운 주소(`account_blocks`)는 코드를 입력한 뒤 `403 ACCOUNT.REGISTRATION_BLOCKED`.
- **계정 이메일은 하나**다. 이유: 로그인 수단 · 비밀번호 재설정 · 중복 가입 판정의 열쇠가 모두 이 주소라, 둘이 되면 "어느 쪽으로 재설정 메일을 보내나 · 둘 중 하나가 남의 계정 것이면" 이 전부 복잡해지고 약한 고리가 늘어난다. "어디로 받을지"는 두 가지로 풀었다: 계정 이메일을 **언제든 바꾸기**(기존 이메일 변경 흐름), 그리고 서비스 안내만 받을 **연락 이메일**(아래).
- 미확인 주소: 이 설정이 켜진 앱에서도 `email-verification=false`(확인 단계 없음)인 앱의 미확인 주소는 "주소 있음"으로 본다 — 확인을 하지 않기로 한 앱이다. `email-verification=true` 에서 미확인 주소의 계정은 로그인 자체가 안 된다(`PENDING_VERIFICATION`).

### 이메일을 받는 시점 — 세 단계 (`email.required-when`)

`email.required` 는 그대로(boolean)이고 **언제 막는가**만 `required-when` 이 더한다. 이름을 새로 짓지 않은 까닭: `required: true` 를 적어 둔 앱 · 환경변수 · 문서가 전부 그대로 맞는다.

| 단계 | 설정 | 주소 없는 계정이 할 수 있는 것 | 막히는 것 | `GET /account/me` |
|---|---|---|---|---|
| 안 받음 | `required: false` (모듈 기본) | 전부 | 없다 | 관련 키 없음 |
| **쓰기 때** | `required: true` + `required-when: WRITE` (견본 앱) | 읽기 전부 + 허용 목록의 변경 | 허용 목록 밖의 **변경 요청** → `403 ACCOUNT.EMAIL_REQUIRED` | `emailRequired: false` · **`emailRequiredToWrite: true`** |
| 로그인 직후 | `required: true` (`required-when` 기본 `SIGN_IN`) | 허용 목록만 | 나머지 전부 → 같은 `403` | `emailRequired: true` |

- `required: false` 인데 `required-when: WRITE` 면 **기동 실패**(설정 이름을 말한다) — "쓰기 때로 켰다"고 믿는데 꺼져 있는 사고를 막는다. `required-when` 을 적지 않은 앱은 한 글자도 바뀌지 않았다(`EmailRequiredInterceptorTest` · `EmailRequiredWebTest` · `EmailRequiredWiringTest` 가 무수정으로 통과).
- **같은 인터셉터에 판정 하나**가 는다: `required-when=WRITE` 이고 메서드가 **`GET` · `HEAD` · `OPTIONS` 글자 그대로**(대소문자 구분 — `get` 은 쓰기로 취급해 막는다)면 통과, 그 밖의 메서드는 허용 목록 밖에서 `403`. 판정에 쓰는 것은 **실제로 도는 메서드**다 — `X-HTTP-Method-Override` · `X-Method-Override` 헤더나 `_method` 파라미터가 `POST` 를 `GET` 으로 바꾸지 못한다. 범위 · 경로 읽기 · 익명 · preflight · 에러 코드와 본문은 로그인 직후 방식과 같다.
- **"쓰기 때"의 허용 목록** = 고정 목록 + 아이디 두 경로(아이디를 켰을 때) + **`PATCH /api/v1/account/me`**(이 방식에서만 — 언어 · 시간대 · 닉네임을 바꿨다고 이메일 화면이 뜨면 안 된다) + 앱의 `email.extra-exclude-paths`.
- `emailRequired` 의 뜻은 그대로("지금 주소가 없어 허용 목록 밖이 **전부** 막힌다")라 이 방식에서는 키가 있고 늘 `false` 다. 새 키 `emailRequiredToWrite` 는 이 방식에서만 있고 주소가 없으면 `true`. 프론트는 로그인 직후 막을 `emailRequired === true` 일 때만 연다.
- 시험: `EmailOnWriteInterceptorTest` · `EmailOnWriteWebTest` · `EmailOnWriteDiscardTest`, `AccountAutoConfigurationTest` `required-when WRITE without required stops the context…`, 견본 앱 `EmailOnWriteJourneyIntegrationTest` · `WriteGateCensusTest`.

#### 무엇이 읽기이고 무엇이 쓰기인가

메서드로 가르는 규칙은 단순한 대신 경계에 걸리는 것이 있다. **기본이 "막힘"** 이다 — 새 변경 엔드포인트는 누가 목록에 적지 않는 한 막힌다. 결제가 실수로 열리려면 누군가 그 경로를 yml 에 적어야 한다.

| 종류 | 예 | 취급 |
|---|---|---|
| 읽기 (`GET` · `HEAD` · `OPTIONS`) | 목록 · 상세 · 알림함 · 실시간 알림 스트림(`GET /notifications/sse`) | **통과** |
| 읽기처럼 보이는 `POST` | 첨부 보기용 `POST /storage/presign-download`, (앱의) 검색 `POST` | 메서드 규칙으로는 막힌다 → **앱이 `extra-exclude-paths` 에 적어 연다** |
| 자기 계정 살림 | `PATCH /account/me`, 세션 닫기(`/auth/**`), 약관 동의(`/legal/**`) | **통과** — `account` 가 아는 경로라 코드에 고정 |
| 사소한 쓰기 (다른 모듈) | 알림 읽음 표시 `PATCH /notifications/{eventId}/read` · `PATCH /notifications/read-all` | 알림함을 여는 것은 읽기인데 화면이 읽음 표시를 보낸다 → **앱이 `extra-exclude-paths` 에 적는다**(`account` 는 다른 모듈의 경로를 모른다) |
| 남에게 보이거나 남는 쓰기 | 글 · 댓글 · 반응 · 작성 프로필 만들기 · 첨부 올리기(`presign` · `multipart/*`) · 노트 · 내보내기 | **`403`** |
| **결제** | 결제 모듈의 사용자 엔드포인트(변경 메서드) | **반드시 `403`.** 허용 목록에 넣지 않는다 — 영수증 · 환불 안내가 갈 주소가 없는 결제를 만들지 않는다. 제공자가 부르는 웹훅은 익명 호출이라 이 게이트와 무관하다 |
| 계정의 다른 변경 | 소셜 연결 · 해제, 연락받을 이메일, 비밀번호 | `403` (주소가 먼저다) |

**모듈별로 열어 둘 만한 경로 (쓰기 때 방식, `email.extra-exclude-paths`)**

| 모듈 | 열어 둘 만한 것 | 열면 안 되는 것 |
|---|---|---|
| `notification` | `PATCH /api/v1/notifications/*/read` · `PATCH /api/v1/notifications/read-all` | — |
| `storage` | `POST /api/v1/storage/presign-download`(글의 첨부를 보려면 필요) | `presign` · `multipart/*`(올리기) |
| `board` | 없음 | 글 · 댓글 · 반응 · 운영 |
| `author-profile` | 없음 | 프로필 만들기 · 고치기 |
| `legal` | 코드에 고정(`/legal/**`) — 적을 것 없음 | — |
| `payment*` | **없음 — 적지 않는다** | 주문 · 결제 · 환불 전부 |

무엇이 "사소한가"는 서비스의 판단이라(알림 읽음은 사소하지만 어떤 앱에서는 반응도 사소하다고 볼 수 있다) 모듈이 스스로 신고하는 SPI 를 만들지 않았다. 모듈은 메커니즘만 내고 사용 방식은 앱 yml 이 정한다. 견본 앱이 켠 세 줄과 이유: [sample.md](sample.md). **견본 앱은 변경 핸들러를 전부 뽑아 주소 없는 계정에게 열린 것이 기대 목록과 정확히 같은지 `WriteGateCensusTest` 가 지킨다**(주소 없는 계정에게 열린 변경 경로는 이 시험의 목록이 정본이다). 다른 앱은 스스로 지켜야 한다 — 반대로 모듈에 새 "사소한 쓰기"가 생겼는데 목록에 안 적으면 읽기 화면에서 이메일 막이 뜬다.

**이 게이트 밖에 있는 것**
- **WebSocket**: MVC 인터셉터는 소켓 핸드셰이크에 **경로와 무관하게** 걸리지 않는다(`/ws/notifications` 를 `/api/` 아래로 옮겨도 마찬가지 — 실측). 스켈레톤의 소켓은 서버가 알림을 내려보내는 용도라 이 게이트가 보지 않는다. **소켓으로 쓰기 메시지를 받는 앱은 그 핸들러에서 직접 계정을 확인해야 한다.**
- **상태를 바꾸는 `GET`** 은 이 게이트에서 읽기다. 스켈레톤 모듈에서 그런 `GET` 은 게시글 상세의 조회수 증가 하나뿐이고, 쓰기로 보지 않기로 했다. `GET /sessions` · `/me` 계열 · `legal /me` 는 컨트롤러만 읽고 서비스 안쪽은 따라가지 않았다.

#### 첫 이메일 등록 · 버리기는 어떻게 달라지나

- 첫 등록의 재인증 면제(1시간 창)는 **규칙 그대로**다. 로그인 직후 방식에서는 거의 늘 창 안이었지만 **쓰기 때 방식에서는 대부분 창 밖**(며칠 읽다 처음 쓰는 사람의 세션은 오래됐다)이라 보통 길이 `403 ACCOUNT.REAUTH_REQUIRED` → 제공자 재확인(`socialReauth`)이다. 훔친 세션이 남의 계정에 자기 주소를 심는 길을 계속 막는 대가이고, 그래서 프론트는 쓰기 화면에 들어서기 전에 미리 알려 글을 잃지 않게 한다(`emailRequiredToWrite`). 제공자 화면으로 페이지를 떠나면 붙잡은 호출과 쓰던 글은 사라진다.
- 막힌 쓰기는 `403` 이고 프론트는 등록 화면을 연 뒤 **같은 호출을 그대로 한 번 더** 보낸다(같은 `Idempotency-Key` · 본문). 쓰기 때 방식의 화면에는 "나중에 하기"가 있다.

### 첫 이메일 등록의 재인증 면제 — 규칙과 이유 (위협)

면제는 **전부** 맞을 때만이다: `email.required=true` · 계정에 주소가 없다(= 한 번도 없었다 — 주소는 비워지지 않는다, 지워진 계정만 NULL) · 계정이 ACTIVE · **이 요청의 세션이 `email.first-registration-window`(기본 1시간) 안에 *로그인으로 열렸다***. 아니면 평소처럼 제공자 재동의(`socialReauth`). `0` 이면 면제 없음.

- **왜 위험한가**: 주소가 없는 계정의 열쇠는 제공자뿐이다. 주소가 붙으면 비밀번호 재설정 · 매직 링크라는 새 길이 생긴다 — 탈취된 세션이 공격자의 주소를 심으면 계정이 넘어간다.
- **면제는 계정이 아니라 세션에 붙는다.** 계정의 `last_login_at` 을 보면 틀린다: 공용 PC 의 옛 세션 · 유출된 리프레시 토큰을 쥔 공격자는, **주인이 다른 기기에서 로그인하는 순간** 같은 계정이라는 이유로 창이 열려 자기 주소를 심고(`password/forgot` → reset 으로 비밀번호 수단까지) 계정을 가져간다. 그래서 `auth-session` 이 구현하는 계약 `SessionInspector.openedAt(accountId, sessionId)` 로 **요청 세션 자체**가 열린 시각을 읽는다. 로그인은 세션을 새로 열고(그때 `created_at`), 리프레시 토큰을 돌려도 세션은 이어지므로 시각이 옮겨지지 않는다 — 옛 세션은 영원히 창 밖이다. 닫힌 · 모르는 · 남의 세션, 세션이 없는 토큰(JWT 만 쓰는 구성), `auth-session` 이 없는 구성은 시각이 없어 **면제 없음**(= 제공자 재동의)으로 안전하게 떨어진다.
- **좁히는 법**: ① 위 세션 단위 창(로그인으로부터 1시간). ② 주소가 **한 번도 없던** 계정만이라 기존 사용자의 주소를 갈아끼울 수 없다. ③ 코드는 공격자의 메일함에서 **요청한 그 세션**으로 입력해야 한다(세션에 묶임). ④ 계정당 시간당 5번 · 주소별 예산은 가입과 같은 버킷. ⑤ 확인되는 순간 그 세션만 남기고 **다른 세션을 모두 닫고**(`EMAIL_CHANGED` 이벤트 `via=first_registration`, 앱이 듣는 감사 · 경보 고리), 이미 열린 다른 코드 · 링크도 닫는다. 옛 주소가 없으므로 알림 메일은 없다.
- **남는 위험(수용)**: 주인이 막 로그인한 **그 세션 자체**를 1시간 안에 가진 공격자(= 주인의 로그인 직후 토큰을 훔친 경우)는 주소를 심을 수 있다. 그 순간 주인의 다른 세션은 닫힌다 — 주인은 로그아웃된 것으로 알아챈다. 더 엄격히 하려면 `first-registration-window: 0`(항상 제공자 재동의).
- **프론트가 겪는 차이**: 로그인 직후는 그대로 된다. 앱을 오래 열어 둔 세션(또는 리프레시로 이어 온 세션)은 첫 시도에 `403 ACCOUNT.REAUTH_REQUIRED` 를 받으니 `socialReauth`(제공자 새 인가 코드)를 실어 다시 부르거나 다시 로그인하게 한다.

### 이메일 없는 계정 버리기 (`POST /account/discard`)

> 이름과 뜻: 예전 문서의 "등록 미완료 계정"은 로그인 직후 방식에서 주소 없는 계정이 몇 분만 살던 때의 말이다. **쓰기 때 방식에서는 주소 없는 계정이 몇 달 살 수 있고** 그동안 아이디를 정하고 약관에 동의하고 닉네임 · 언어를 바꾸고 알림을 읽을 수 있으므로 **"이메일 없는 계정 — 아무것도 쓰지 못한 계정을 유예 없이 지운다"** 로 부른다. 조건은 두 방식에서 같다(아래). 유예를 건너뛰어도 되는 근거는 "허용 목록 밖의 쓰기를 한 번도 하지 못했다 — 글 · 댓글 · 반응 · 첨부 · 결제가 없다"이고 두 방식에서 똑같이 성립한다. **아이디를 정한 계정도 버릴 수 있고, 그 아이디는 다시 쓸 수 없다**(위 "재사용 금지") — 확인 대화상자가 그 사실을 말하고, 아이디를 **정하기 전** 화면(아이디 게이트)에도 이 길을 둔다. 앱이 `extra-exclude-paths` 로 쓰기를 넓게 열었다면 그 계정에 글이 있을 수 있다 — 같은 지우기 길이 글을 "삭제된 사용자"로 돌리며 유예가 없다는 점을 그 설정의 몫으로 안다. 시험: `EmailOnWriteDiscardTest` · `EmailOnWriteWebTest` `E11 - …`.

이메일로 가입해 둔 사람이 Kakao 로 로그인하면 **주소 없는 새 계정**이 생긴다(제공자가 주소를 안 주므로 자동 병합이 없다). 등록 화면에 자기 주소를 넣으면 코드 대신 "이미 이 주소의 계정이 있어요" 메일이 가고, 안내대로 기존 계정에 로그인해 Kakao 를 연결하려 하면 Kakao 가 껍데기 계정에 붙어 있어 `409 IDENTITY_TAKEN` 이다. 껍데기를 일반 탈퇴(유예 30일)해도 그동안 수단이 잡혀 막다른 길이다. 그래서:

- **조건(엄격, 어긋나면 `409 ACCOUNT.DISCARD_NOT_ALLOWED`)**: `email.required=true` · 계정에 주소가 없다(한 번도 없었다) · ACTIVE. **정지 계정은 못 한다**(정지는 박제), 마지막 관리자는 못 한다(`ACCOUNT.LAST_ADMIN`). 계정 나이 상한은 두지 않는다: 껍데기에 다시 들어와 버리는 것이 정상 경로이고(어제 만든 껍데기를 오늘 버린다), 상한을 두면 그 뒤 다시 막다른 길이 된다.
- **다시 인증**: 일반 탈퇴와 같다. 단 **방금 로그인한 그 세션**(첫 이메일 등록과 같은 창)이면 면제, 옛 세션은 제공자 새 인가 코드(`socialReauth`). `email.required` 를 나중에 켜서 이미 데이터가 있는 주소 없는 계정이 생긴 앱에서도 같은 증명이 필요하다 — 그 계정을 버리면 다른 모듈의 `AccountErasureListener` 가 그 데이터를 지운다(일반 탈퇴 뒤 정리와 같다).
- **동작**: 상태를 DELETED(유예 없음)로 바꾸고 모든 세션을 닫은 뒤 곧바로 정리 길(`AccountPurgeService`)을 탄다 — 다른 모듈의 지우기 고리를 모두 부르고, `deletion.mode` 대로 ERASED 행(기본, 개인정보 없음) 또는 행 삭제. 소셜 수단이 풀려 기존 계정에서 연결할 수 있다. 고리가 실패하면 `503 ACCOUNT.ERASURE_RETRY`: 계정은 이미 로그아웃된 DELETED(정리 대상)이고, 같은 호출을 되풀이하거나 정리 잡이 끝낸다. 이벤트 `ACCOUNT_DISCARDED`(`via` = `fresh_login` | `reauth`).
- **이메일 필수를 켜기 전부터 있던 글 있는 주소 없는 계정**도 같은 길로 버려진다 — 기존 계정에도 해당하며, 그 글은 삭제된 사용자로 남는다(위 "재인증" 항목: 다른 모듈의 `AccountErasureListener` 가 정리한다).
- **남용 검토**: ① 정지 회피 — 정지 계정은 불가. ② 차단 회피 — 이 경로는 차단 해시를 만들지도 지우지도 않는다(운영자가 지운 계정의 차단은 그대로 가입을 막는다). ③ 반복(버리고 다시 소셜 로그인) — 매번 제공자 로그인이 필요하고 로그인 한도(IP · 계정)를 지나며, 메일은 한 통도 나가지 않고 데이터는 만들어지지 않는다. ④ 탈취된 옛 세션이 계정을 없애기 — 재인증 없이는 못 한다(위 세션 단위 창).

### 소셜을 끊은 뒤 다시 연결하려다 막힌 길 (`409 ACCOUNT.IDENTITY_TAKEN`)

계정 A 가 설정에서 소셜(예: LINE)을 **끊은 뒤 그 소셜로 다시 로그인**하면 제공자 주체가 비어 있으니 **새 계정 B** 가 만들어진다(자동 병합은 없다). A 에서 그 소셜을 다시 연결하려 하면 B 가 쥐고 있어 `409 ACCOUNT.IDENTITY_TAKEN`. B 에 주소까지 있으면(`email.required` 를 만족한 계정) `discard` 도 못 한다(주소 없는 계정만). 출구:

1. 그 소셜로 로그인해 **B** 에 들어간다.
2. B 의 수단이 그 소셜 **하나뿐**이면 끊을 수 없다(`LAST_SIGN_IN_METHOD`) — B 에서 **비밀번호를 정하거나**(`POST /account/password/change`, 메일로 받은 `confirmationCode`) 재설정해 수단을 둘로 만든다.
3. B 에서 **그 소셜을 끊거나 B 를 탈퇴**한다(탈퇴는 유예 동안 수단이 잡혀 있으니 끊는 쪽이 빠르다).
4. **A** 로 로그인해 그 소셜을 연결한다.

시험: `DetachedSocialExitPathTest` (B 가 첫 비밀번호를 정한 뒤 소셜을 끊으면 A 가 연결할 수 있다). 프론트는 `IDENTITY_TAKEN` 을 받으면 이 안내를 그대로 보여 준다(`ACCOUNT.IDENTITY_TAKEN` 은 누가 쥐고 있든 같은 코드다 — 다른 사람의 계정이 아니라 *내가 만든 다른 계정* 일 수 있다고 알린다).

> `IDENTITY_TAKEN` 은 **다시 인증과 제공자 코드 교환을 마친 뒤에만** 알린다 — 증명 없이 남의 계정에 그 소셜이 붙어 있는지 알 수 없게. 그래서 같은 제공자를 이 계정이 이미 가졌거나(`IDENTITY_EXISTS`) 마지막 수단을 끊으려는(`LAST_SIGN_IN_METHOD`) 처음부터 실패할 요청만 증명(메일 코드 · 제공자 코드)을 쓰기 **전에** 거절한다.

## 연락 이메일 (`skeleton.account.contact-email.enabled`, 기본 꺼짐)

계정 이메일(로그인 · 재설정 · 중복 판정의 열쇠, 계정당 하나) 외에 **서비스 안내를 받을 주소**를 하나 더 둘 수 있다(구글의 보조 메일처럼). 로그인 수단이 아니고, 복구 수단이 아니고, 유니크하지 않다(다른 계정의 이메일 · 연락 이메일과 같아도 된다 — 그래서 "이미 쓰인 주소" 신호가 없다). 켜지 않으면 엔드포인트도 없고(404) `me` 에 `contactEmail` **키가 없다** — 켠 앱에서 아직 없으면 키는 있고 값이 `null` 이라 프론트가 "꺼짐" 과 "아직 없음" 을 가를 수 있다. `email.required` 가 요구하는 것은 **계정 이메일**이고 연락 이메일은 늘 선택이다.

**어디로 무엇이 가나** (시험: `MailRoutingTest` — 받는 주소별 통수로 단정한다. 이 표와 시험 이름이 서로를 가리킨다):

| 메일 | 받는 주소 | `MailRoutingTest` |
|---|---|---|
| 가입 인증번호 · "이미 계정이 있어요" | 가입하려는 주소 · 그 주소의 계정 이메일 | `signing up with the account address…` · `a contact address is not an occupied address…` |
| 비밀번호 재설정 링크 | **계정 이메일 한 통** (연락 이메일 주소로 요청하면 아무 메일도 안 나간다 — 연락 이메일로는 계정을 찾지 못한다) | `password reset goes to the account address only…` · `asking for a reset with the contact address sends nothing…` |
| 매직 링크 | 계정 이메일. 연락 이메일 주소는 그저 주인 없는 주소 — 가입이 열린 앱에서는 그 주소로 *새* 가입 링크가 가고 연락 이메일을 가진 계정과는 무관하다 | `auth-magic-link` `MagicLinkContactEmailTest` |
| 계정 이메일 변경 코드 | 새 계정 이메일 후보만 (옛 계정 이메일에 "요청됨" 알림, 연락 이메일 0통) | `an email change code goes to the new account-address candidate only…` |
| 다시 인증 · 삭제 확인 코드 · 삭제 예약 알림 | 계정 이메일 | `re-authentication and deletion codes and the deletion notice…` |
| 로그인 수단 추가 · 비밀번호 변경 알림 | 계정 이메일 | (같은 `core.mailer` 경로 — 받는 주소는 `accounts.email` 에서만 읽는다) |
| 연락 이메일 확인 코드 | 연락 이메일 후보 | `registering, changing and removing the contact address…` |
| 연락 이메일 등록 · 변경 · 삭제 알림 | **계정 이메일**(주소는 싣지 않는다) | 같은 시험 |
| **서비스 알림**(메일 채널로 "사용자에게 연락"하는 것) | **연락 이메일, 없으면 계정 이메일** — platform SPI `ContactAddresses.contactEmail(accountId)` 가 푼다(`account` 가 구현, `@ConditionalOnMissingBean`) | `the service contact address is the contact address when there is one…` |

- **왜 복구 수단이 아닌가**: 복구 경로가 둘이면 약한 쪽이 열쇠가 된다 — 계정을 여는 모든 것은 계정 이메일 하나를 지난다. 그래서 `accounts.email` 만 읽는 조회(`accountByEmail`)가 재설정 · 매직 링크 · 가입 판정의 유일한 길이고, 연락 이메일 열은 그 어느 것도 보지 않는다.
- **재인증은 없다(계정 이메일이 있는 계정은 일반 로그인 상태로 충분)**: 이 주소는 계정을 열 수 없다. 서비스 안내에 민감한 내용이 실릴 수 있으므로 대신 **등록 · 변경 · 삭제마다 계정 이메일로 알림 한 통**이 가고(`CONTACT_EMAIL_CHANGED` 이벤트), 코드는 요청한 세션에서만 · 주소별 예산 공유 · 계정당 시간당 5번이다. 민감한 알림을 연락 이메일로 보내지 않을 책임은 보내는 앱에 있다. 알림 메일의 조치는 "계정 설정에서 연락받을 이메일을 확인하고 지울 수 있어요(비밀번호도 바꿔 주세요)" 다. **계정 이메일이 없는 계정**(`email.required=false` 인 앱의 Kakao 등)은 알림이 갈 곳이 없어 조용히 바뀌므로, 등록 · 변경에 **제공자 새 인가 코드 재인증**(`socialReauth`)을 요구한다.
- **주인의 복구 동작이 연락 이메일도 비운다**: 연락 이메일은 로그인 수단이 아니라서 탈취된 세션이 (재인증 없이) 공격자 주소를 심어 둘 수 있다 — 주인이 계정을 되찾은 뒤에도 서비스 알림이 공격자에게 가면 안 된다. 그래서 ① 메일함 증명(비밀번호 재설정 · 가입 확인이 미확인 계정을 이어받는 경우 · 메일함을 증명하는 로그인으로 미확인 계정이 확인되는 경우: `proveMailbox`), ② 현재 비밀번호(또는 메일 코드)로 하는 비밀번호 변경, ③ 사용자가 직접 한 "모든 기기에서 로그아웃"(`DELETE /auth/sessions`)이 연락 이메일을 비운다(`CONTACT_EMAIL_CHANGED` 이벤트 `via=cleared_by_…`; 열려 있던 연락 이메일 코드도 같이 죽는다). 확인된 계정의 일반 매직 링크 로그인은 복구로 치지 않는다 — 그 수단을 주로 쓰는 사람의 연락 주소가 매 로그인마다 사라지지 않게. 내부에서 세션을 닫는 일(이메일 변경 · 관리자 정지 등)은 이 이유(`USER_REVOKED_ALL`)를 쓰지 않는다.
- **`ContactAddresses`(서비스 알림 SPI)**: **ACTIVE 계정만**, 연락 이메일이 있으면 그것(저장되는 것은 코드를 입력한 주소뿐), 없으면 **확인된** 계정 이메일. 정지 · 삭제 중 · 지워진 · 이메일 미확인 계정은 `null`. **아직 이 SPI 를 부르는 소비자(알림 메일 채널)는 이 레포에 없다** — 계약만 있다.
- **기능을 켰다가 끈 뒤 남는 주소**: 끄면 엔드포인트가 사라지고 남은 주소는 읽히지 않는다(`ContactAddresses` · `me` 가 무시). 행의 값은 그대로 남으므로 지우려면 운영에서 `update accounts set contact_email = null, contact_email_verified_at = null` 하거나 탈퇴 익명화(`erase`)를 기다린다 — 끈 상태에서 삭제 API 를 열어 두지 않기로 했다(기능이 꺼진 앱의 API 표면을 늘리지 않는다).
- **운영자 차단(`account_blocks`)**: 지운 주소도 연락 이메일로 쓸 수 있다. 차단은 *등록*(가입 · 계정 이메일 · 소셜 연결)을 막는 것이고 이 주소로는 로그인도 복구도 못 하므로 막아 얻는 것이 없다(그 주소 주인에게 안내 메일이 가는 것뿐 — 코드 입력으로 소유가 증명된 뒤).
- **저장**: `accounts.contact_email` · `contact_email_verified_at`(확인된 주소만, 유니크 · 인덱스 없음; `verified_at` 은 코드를 입력한 시각의 기록용이고 저장 자체가 확인 뒤라 읽어서 판정하지는 않는다). 탈퇴 익명화(`erase`)가 둘 다 NULL 로 비운다(DELETE purge 는 행째). 시험: `JdbcErasureDbTest`(양 방언의 표 전체 스캔), `AccountJourneyIntegrationTest`(샘플 전체 표 스캔), 업그레이드 시험.
- 관리자 DTO 에는 싣지 않는다(개인정보 최소화).

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
| `accounts.id`, `created_at`, `status=ERASED`, `erased_at`, `deleted_at`(탈퇴를 요청한 시각), `updated_at`(= 지운 시각), `email_verified=false` | `email`(→ NULL, 유니크 키에서 풀려 같은 주소가 새로 가입한다 — 두 DB 시험), `login_id`(→ NULL — **단 아이디는 다시 쓸 수 없다**: 아래 표 `account_used_login_ids` 에 해시가 남는다), `display_name` · `display_name_key` · `display_tag`(→ NULL, 닉네임도 풀린다), `locale`, `time_zone`, `suspended_reason`, `last_login_at`, `purge_after` |
| `account_audit` 의 **사건 줄**(종류 · 시각 · 계정 id) | 같은 계정 줄의 `ip` · `detail` (→ NULL). 다른 계정 줄은 그대로 |
| `account_used_login_ids` 의 해시(아이디 원문 없음 · 계정 id 없음 · `used_at` 은 UTC 날짜 — 모든 삭제 길에서 남는다. 재사용 금지) · (운영자 지우기만) `account_blocks` 의 해시 | 로그인 수단 전부(비밀번호 해시 · 제공자 주체 · 매직 링크), 역할, `account_tokens`(계정 id 로 걸린 것 + 그 주소가 주인인 것), `account_challenges`(계정 id · 그 주소의 가입 시도 — IP 포함), 이메일 변경 대기 |
| 각 모듈이 정한 것: board 는 작성자를 `deleted:<해시>` 로, legal 은 동의 기록의 사람을 지우고 증거는 남김(`erasure.mode`) | auth-session 의 세션 · 리프레시 토큰(IP · UA · 기기 이름), notification-jdbc 의 받은편지함 |

- 정리는 **계정 하나당**: ① 조건 재확인 + **선점**(`claimErasure`, 리스너 전) ② 다른 모듈의 고리 ③ 한 트랜잭션(`AccountRepository.erase` · `purge`: 계정 행 락 → 조건 재확인 → 위 표의 행들 → 계정 갱신 · 삭제). `account-jdbc` 의 `erase` · `purge` 는 `account_tokens` · `account_challenges` · `account_audit` 를 **같은 트랜잭션에서 직접** 지운다 — 그 표들의 저장소(`OneTimeTokenStore` · `ChallengeStore`)를 앱이 바꿨다면 그 앱의 `AccountRepository.erase` 가 자기 표를 같이 지워야 하고, 못 지운 줄은 서비스가 저장소 호출 전에 닫는 것(`closeSensitiveLinks`) + 만료 청소(`cleanup.expired-retention`)가 마지막 방어다. 다른 모듈의 고리는 그 앞에서 **모두** 불리고, 하나라도 실패하면 계정은 `DELETED` 그대로 다음 주기에 다시 한다 — 고리는 멱등이어야 한다.
- 한 번에 `deletion.purge-batch`(50) 개까지, 여러 인스턴스는 `account_locks` 임대를 쥔 하나만 정리한다 (잡 큐가 있으면 주기마다 한 인스턴스만 잡 `account-purge` 를 넣는다).
- **만료된 코드 · 토큰 청소**: `cleanup.expired-retention`(기본 1d) 이 지난 줄을 같은 주기 정리가 지운다. **만료는 읽을 때 검사**하므로 정리가 늦거나 꺼져 있어도 만료된 코드가 쓰이지는 않는다 — 이 값은 표가 쌓이지 않게 하는 청소 기준일 뿐이다.

### 정지는 박제다

- 정지된 계정은 탈퇴를 요청할 수 없다(`403 ACCOUNT.SUSPENDED_CANNOT_DELETE`). 탈퇴 유예 중에 정지되면 유예가 끝나도 **지워지지 않고** 막힌 채 남는다 — 관리자가 정지를 풀면 탈퇴가 이어지고(유예는 새로 시작), 명시적으로 `POST /admin/accounts/{id}/erase` 하면 지워진다.
- 로그인 수단 행이 남으므로 같은 이메일 · 같은 제공자 주체로 새 계정을 만들 수 없다 — 소셜 로그인은 같은 계정의 `AUTH.ACCOUNT_SUSPENDED`, 코드로 가입한 미확인 정지 계정을 메일함 증명으로 이어받는 길은 `403 ACCOUNT.REGISTRATION_BLOCKED`.
- 정지된 계정은 **로그인 수단을 끊지도 붙이지도 못하고 첫 비밀번호도 정하지 못한다**(`403 ACCOUNT.NOT_ACTIVE`; 탈퇴 유예 중인 계정도 같다). 정지 전에 발급된 액세스 토큰이 15분 남아 있어도, 주소 없는 소셜을 끊고 같은 제공자 계정으로 새 계정을 만들어 정지를 빠져나갈 수 없다. 판정은 계정 행 락 안에서 한다(정지가 확인과 변경 사이에 끼어들지 못한다). 시험: `FrozenAccountTest` · `JdbcIdentityGuardDbTest`. 비밀번호 **변경**(이미 있는 비밀번호 교체) · 이메일 변경 · 연락 이메일 · 닉네임 같은 다른 계정 변경은 이 규칙 밖이다(수단의 수가 변하지 않는다).
- **운영자가 정지 계정을 지우면** 재가입 차단이 남는다: 이메일과 각 제공자 주체의 **HMAC-SHA256 해시**(서버 비밀 `blocks.secret`)만 `account_blocks` 에 사유 · 시각 · 만료와 함께 둔다 — 원문은 어디에도 없다. 가입 **요청**은 늘 같은 202(존재 · 차단 여부가 드러나지 않는다), **메일함을 증명한 사람**(코드 확인 · 매직 링크) 또는 **제공자 계정을 증명한 사람**(소셜)만 `403 ACCOUNT.REGISTRATION_BLOCKED` 를 본다(이메일 확인을 끈 앱의 가입은 "이미 있는 주소" 와 같은 `409 EMAIL_TAKEN`). 보존은 `blocks.retention`(기본 `0` = 운영자가 지울 때까지), 관리자가 `GET/DELETE /admin/accounts/blocks` 로 보고 푼다.
- ⚠ **개인정보 처리방침에 적을 것(앱의 몫)**: ① 탈퇴 뒤에도 계정 id · 가입 시각 · 탈퇴 시각 · 감사 사건(IP 없이)이 남는다 ② 운영자가 이용 제한 중인 계정을 지우면 재가입을 막으려고 이메일 · 로그인 계정의 **되돌릴 수 없는 해시**를 `blocks.retention`(기본 무기한) 동안 보관한다 ③ 앱이 쓰는 다른 표(주문 · 결제 …)의 보존은 앱이 정한다 ④ (로그인 아이디를 켠 앱) 탈퇴 뒤에도 아이디의 **되돌릴 수 없는 해시**가 재사용을 막으려고 **기한 없이** 남는다(원문 · 계정 id 없음). ⑤ (`payment` 모듈을 쓰는 앱) 결제 기록(주문 번호 · 상품 · 금액 · 결제 수단 종류 · 결제사 결제 번호 · 시각)을 탈퇴 뒤에도 법정 기간 보관한다는 것, 카드사와 카드 번호 끝 네 글자는 탈퇴 때 지운다는 것, 결제 처리를 결제사에 맡긴다는 것 — 탈퇴 때 결제 줄이 어떻게 되는지와 근거는 `docs/modules/payment.md` 「탈퇴 · 익명화 · 보존」. **차단 해시의 키**: `blocks.secret`(환경변수 `<P>_ACCOUNT_BLOCKS_SECRET`, 선언의 `secrets:` — 무작위로 충분)이 비면 **JWT 비밀에서 파생**한다. 그러면 **JWT 비밀을 돌릴 때 모든 차단이 조용히 풀린다**(해시가 달라져 맞지 않는다) — 그래서 관리자 API 를 켠 앱은 보호 환경에서 이 값이 비면 `DeployGuard` 경고가 난다(기동을 막지는 않는다: 차단을 쓰지 않는 앱도 있다). **로그인 아이디를 켠 앱은 경고가 아니라 기동을 막는다**(같은 키로 아이디 해시를 만든다 — 위 "재사용 금지"). `blocks.secret` 자체를 바꿔도 기존 차단은 맞지 않으므로 처음부터 따로 두고 바꾸지 않는다.

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

템플릿 ko · en (`AccountMailTemplates` 빈으로 교체), 계정 로케일로 고르고 없으면 `mail.default-locale`. 링크(재설정 · 매직 링크)의 앞부분은 `mail.link-base-url`; 가입 · 이메일 변경 · 다시 인증 · 삭제는 링크가 없는 코드 메일이고, 아이디 찾기(`LOGIN_ID_REMINDER`)는 토큰 없는 로그인 페이지 링크만 있는 메일이다(아이디가 메일에 나오는 곳은 이 한 통뿐 — 비밀번호 재설정 메일에는 싣지 않는다). 전달 길은 `notification-mail` 의 `MailSender`; 없으면 `LogOnlyMailTransport` 가 **보내지 않고** 알린다. 링크(토큰)는 앱이 `mail.log-links` 를 켠 로컬에서만 로그에 나온다 (보호 환경에서 켜면 DeployGuard 문제). 토큰은 어떤 이벤트 · 로그 줄에도 없다.
로컬 개발은 compose 의 `mail` 프로필(mailpit, `localhost:8025`)이 받는다 — `apps/sample` 이 그렇게 쓴다.

## 이벤트 · 감사 · 경보

모든 인증 사건(가입 · 확인 · 로그인 성공/실패/제한 · 비밀번호 · 세션 · 연결/해제 · 정지 · 역할 · 삭제 · 지움 · 아이디 정하기 `LOGIN_ID_SET`(`via` 만, 값 없음) · 아이디 찾기 메일 `LOGIN_ID_REMINDER_SENT`(계정 id 만) · 관리자의 작성자 프로필 연결 조회 `AUTHOR_LINK_VIEWED` — `author-profile` 의 `admin.enabled` 일 때만, 대상 계정 + `by`, 프로필 id 는 싣지 않는다)은 `AccountEventPublisher` → `AccountEventListener` 빈들로 간다 (기본: 한 줄 로그, 이메일 · 토큰 없음). `skeleton.account.audit.enabled=true` + `account-jdbc` 면 `account_audit` 표에도 쓴다. `alert` 모듈이 있으면 로그인 시도 폭주 · 리프레시 토큰 재사용이 주인 경보로 간다.

## 배포 가드 (`skeleton.env=stage|prod` 또는 auth 의 보호 프로필)

기동이 막히는 것: `skeleton.web.client-ip.mode` 미설정 · 메모리 계정 · 토큰 저장소 · 메모리 세션 저장소 · 메일 발송 길 없음 · `mail.link-base-url` 비어 있음 · `mail.log-links=ON` · 시드 계정 · 캡차를 필수로 했는데 검증기 없음 · 쿠키 전달인데 `cookie.secure=false` · 잘못된 부트스트랩 이메일 · **로그인 아이디를 켰는데 `blocks.secret` 이 빔**(탈퇴한 아이디의 재사용 금지가 JWT 비밀을 돌릴 때 조용히 풀린다). 기동이 막히는 것에 메모리 `ChallengeStore`(코드가 재시작에 사라지고 인스턴스끼리 못 나눈다)도 있다. 경고: 가입이 열려 있는데 캡차 없음 · 로그인 제한 끔 · 이메일 확인 끔(+ 매직 링크 · 소셜 병합과 함께면 따로).

## HTTP 계약

프론트가 쓰는 계약 전체(엔드포인트 · 요청/응답 JSON · 오류 코드 · 순서)는 [account-http-contract.md](account-http-contract.md).

## 정해 둔 것 (주인이 뒤집을 수 있는 것)

- **bcrypt(기본) vs argon2**: 추가 의존이 없고 해시가 메모리를 쓰지 않아 로그인 폭주 때 메모리 DoS 면이 작다. argon2id 는 opt-in(`password.encoder=argon2` + BouncyCastle). 델리게이팅 인코더라 `{bcrypt}` 접두사로 저장되고 옛 접두사 없는 bcrypt 도 검증되며 로그인 때 새 방식으로 올라간다.
- **리프레시 전달 기본 body**: 쿠키가 없어 CSRF 면이 없고 react `api-client` 가 바로 쓴다. 대가는 XSS 가 토큰을 읽을 수 있다는 것 — 회전 · 재사용 탐지 · 짧은 액세스 토큰이 피해를 줄인다. 쿠키가 더 낫다고 판단하면 `delivery: COOKIE`.
- **로그인 한도는 시도 전부**를 센다 (위 위협 표).
- **뒤로 넘기는 일(메일 · 재설정 조회)은 프로세스 안의 작은 풀**이다 — 종료 때는 기다려 마치고 넘치면 부른 스레드가 하지만, 비정상 종료로 처리 못 한 메일은 사라지고 사용자가 다시 요청한다(재전송). **가입 계정 행은 요청 스레드가 저장한다**(202 를 받은 가입은 사라지지 않는다). 내구성이 더 필요하면 `AccountTaskRunner` 빈으로 잡 큐에 넣는다.
- **액세스 토큰은 상태 없는 JWT** — 정지 · 역할 변경은 일반 API 에는 최대 15분 늦게 반영된다 (새로고침은 즉시 현재 역할). **관리자 API 는 매 호출 저장소를 다시 읽는다.**
- **계정 이메일은 하나, 연락 이메일은 따로 하나**: 위 "이메일 필수" · "연락 이메일". 복수 계정 이메일은 만들지 않는다(로그인 수단 · 복구 · 중복 판정이 전부 복잡해진다).
- **`username` = 이메일** (이 모듈이 만든 계정에는 따로 사용자 이름이 없다). 로그인 아이디를 켜도 토큰 · `GET /auth/me` 의 `username` 은 그대로다 — **아이디는 JWT 에 싣지 않는다**(토큰은 로그 · 프록시에 남을 수 있다). 로그인 **입력**의 `username` 만 `@` 유무로 아이디 또는 이메일로 읽는다(위 "로그인 아이디").
- **로그인 아이디: 비공개 · 못 바꾼다 · 탈퇴해도 다시 못 쓴다 · 기본 꺼짐**: 위 "로그인 아이디". 켜는 것은 앱의 선택이다(스켈레톤은 사용 방식을 정하지 않는다). 규칙은 나중에 넓힐 수는 있어도 좁힐 수는 없다.
- **탈퇴 뒤 기본은 `ANONYMIZE`** (개인정보만 지우고 행은 `ERASED` 로 — 주인의 결정: "연결고리가 끊기면 안 된다"). `DELETE` 로 바꾸면 행까지 지운다. **정지는 박제**(탈퇴로 못 빠져나가고, 운영자가 지우면 해시 차단이 남는다). **탈퇴 취소(`self-restore`)는 기본 꺼짐**(스켈레톤은 사용 방식을 정하지 않는다) — 앱이 켠다.

## 약관 동의 (`legal` 모듈이 있을 때)

가입 요청의 `consents: [{type, version, locale?}]` 는 가입 시도에 실려 저장되고, 코드가 확인되어 계정이 만들어질 때 **같은 트랜잭션에서**(`AccountTransaction`) 기록된다 — 확인되지 않은 시도는 동의를 남기지 않는다. 검사(`SignUpConsentGate.check`)는 요청 본문과 문서만 보므로 응답은 주소와 무관하게 같다. `legal` 이 없으면 `consents` 는 무시된다. 소셜 · 매직 링크 첫 로그인은 동의 없이 계정이 만들어지고 재동의 필터 · `GET /consents/me` 로 세션이 온전히 쓰이기 전에 받는다. 자세히: [legal.md](legal.md), [legal-http-contract.md](legal-http-contract.md).
