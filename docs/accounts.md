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

**가입 → 확인 → 로그인**: `POST sign-up`(늘 202) → 메일 링크 → 프론트가 `POST verify-email` → `POST login`. 확인 전 로그인은 `403 AUTH.EMAIL_NOT_VERIFIED`(비밀번호가 맞을 때만 — 존재를 새로 알리지 않는다).
**조용한 갱신**: API 가 401 → (단일 비행) `POST refresh` → 새 쌍 → 한 번 재시도. `AUTH.REFRESH_*` 면 로그아웃.
**비밀번호 찾기**: `POST forgot`(늘 202) → 메일 → `POST reset`(모든 세션 종료, 이메일도 확인된 것으로).
**이메일 변경**: `POST email/change` → 새 주소로 확인 메일(옛 주소에도 알림) → `POST confirm-email-change` → 바뀜.
**매직 링크**: `POST magic-link/request`(늘 202) → 메일 → `POST magic-link/redeem` → 토큰.
**소셜 연결**: 로그인한 채 `POST identities/social/{provider}`; 마지막 수단은 못 뗀다.
**삭제**: 비밀번호(또는 메일 확인 링크)로 다시 인증 → `202 {purgeAfter}` → 즉시 로그인 불가 · 세션 종료 → 유예(30일) 뒤 `AccountErasureListener` 들이 지우고 계정 행 삭제.

링크를 **열기만** 해서는 아무것도 바뀌지 않는다 — 프론트가 라우트에서 POST 한다 (메일 보안 검사기가 GET 을 미리 열어도 링크가 안 타게).

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
| 소셜 · 제공자가 **확인한** 이메일이 기존 계정과 같다 | 기본: **병합 없이** `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT` — 기존 방법으로 로그인해 설정에서 연결한다. `social.merge-on-verified-email=true` 여도 **양쪽 이메일이 모두 확인된** 경우만 |
| 소셜 · 제공자가 확인하지 **않은** 이메일 | 이메일을 없는 것으로 친다 — 저장하지 않고(남의 주소 선점 방지) 충돌도 알리지 않는다(주소 존재 조회 방지) |
| 매직 링크(메일함 증명) · 같은 이메일의 기존 계정 | 그 계정에 붙는다(`sign-up=false` 여도 — 가입이 아니다) + 이메일 확인으로 친다. 계정이 **미확인**이었다면 가입 때 정해진 비밀번호(메일함 주인이 정한 것이 아니다)는 **버리고** 세션 · 인증 링크를 닫는다 (사전 탈취 방어) |
| 마지막 "들어오는 길" 을 떼려 한다 | `409 ACCOUNT.LAST_SIGN_IN_METHOD` (원자적 — 동시 해제 둘이 다 성공하지 않는다) |

## 이메일 정규화 (한 곳)

`Emails.normalize` = 앞뒤 공백 제거 → 유니코드 NFC → 소문자(`Locale.ROOT`). 점 · `+태그` 제거나 NFKC · IDN 변환은 **하지 않는다** (같게 보면 남의 주소를 같다고 말하게 된다). 저장 · 조회 · 한도 키 · 토큰 주인 · 메일 수신자가 모두 이 값이고, 같은 주소인지는 DB 정렬에 맡기지 않고 `AccountCore.accountByEmail` 이 **저장된 글자와 글자 그대로** 비교한다. MySQL 은 `email` · 토큰 `subject` 열도 `utf8mb4_bin` 이다 (기본 정렬은 악센트 · 대소문자를 같게 본다). 메일은 늘 계정에 **저장된** 주소로 간다.

## 위협 모델 — 각 항목을 덮는 시험

| 위협 | 막는 법 | 덮는 시험 |
|---|---|---|
| **계정 열거 (응답)** | 가입 · 재설정 · 재전송 · 매직 링크 요청은 계정 유무와 무관하게 같은 `202` 본문. 있는 주소 가입은 "이미 계정이 있어요" 메일 | `AccountWebTest` `sign-up answers 202 identically…` · `forgot is 202 and identical…`, `MagicLinkWebTest` `request is 202 with the same body…`, `MagicLinkSignUpClosedWebTest` |
| **계정 열거 (시간)** | 요청 스레드는 검사 · 한도 · 해시 한 번 · (가입은) 저장소 호출 한 번을 새 주소든 있는 주소든 똑같이 하고 토큰 · 메일은 뒤에서. 로그인은 계정이 없어도 해시 비교 한 번 | `AccountResponseLevelSecurityTest` `sign-up, forgot and resend answer without waiting for mail…`, `RegistrationServiceTest` `the request thread stores the account itself - one identical repository call…`, `PasswordServiceTest` `forgot never touches the stores on the request thread…`, `PasswordLoginServiceTest` `unknown account still costs one hash comparison…` |
| **열거 (잠금 오라클)** | 로그인 한도는 입력 식별자를 계정이 있든 없든 똑같이 센다 | `LoginControlsTest` `the eleventh attempt on one identifier… whether or not the account exists` |
| **무차별 대입** | IP 당 · 주소당(`email` · `username` · `accountId` 한 버킷) 시도 수 제한(429 + `Retry-After`), 현재 비밀번호 추측도 계정당 제한, 경보. **IP 출처**: `skeleton.web.client-ip.mode` 를 정해야 한다 — 안 정하면 `X-Forwarded-For` 로 한도가 풀리므로 stage · prod 가드가 기동을 막는다 | `LoginControlsTest`, `EmailNormalizationTest` `the login limit key is one per address…`, `AccountDeployGuardTest` `without a client IP mode…`, `AccountWebTest` `login attempts are throttled…`, `PasswordServiceTest` `guessing the current password…` |
| **메일 폭탄** | 주소당 · IP 당 한도 (넘으면 조용히/429). 알려진 대가: 한 주소의 시간당 예산(3)이 차면 제3자가 그 주소의 인증 · 재설정 · 매직 링크를 막을 수 있다 — 캡차(`captcha.required`)를 권고 | `RegistrationServiceTest` `resend is capped per IP like forgot…` · `resend is capped per address…`, `PasswordServiceTest` `forgot is capped…`, `MagicLinkWebTest` `requests are capped…` |
| **링크 토큰 탈취 · 추측** | 256비트 난수, **해시만 저장**, 한 번만, 용도 구분(인증 링크로 재설정 불가), 만료, 새 링크가 옛 링크를 닫음 | `OneTimeTokensTest`, `JdbcTokenAndAuditDbTest`, `PasswordServiceTest` `a verification link cannot reset a password`, `MagicLinkWebTest` `a link for another purpose…` |
| **링크 이중 사용 (경쟁)** | 소비는 원자적 조건부 UPDATE | `OneTimeTokensTest` `sixteen threads…`, `RegistrationServiceTest` `sixteen concurrent verifications…`, `PasswordServiceTest` `sixteen concurrent resets…`, `MagicLinkWebTest` `sixteen simultaneous redemptions…` (+ PostgreSQL · MySQL `JdbcTokenAndAuditDbTest`) |
| **링크 미리 열기(보안 검사기)** | 열기는 GET, 바꾸는 것은 프론트의 POST | 설계 (정책 실패가 링크를 태우지 않는 시험: `PasswordServiceTest` `a policy failure leaves the link usable`) |
| **리프레시 토큰 탈취 · 재사용** | 불투명 · 해시 저장 · **매번 회전** · 쓴 토큰은 **세션이 끝날 때까지** 기억 · 다시 오면 그 세션 종료 + `REFRESH_REUSE_DETECTED` 이벤트(WARN 로그 · 감사 · 주인 경보) | `SessionServiceTest` `replaying a rotated-away token…`, `SessionReuseMemoryTest`, `SessionBodyDeliveryWebTest` `reuse reaches the listeners…`, `SessionEventBridgeTest` (세션 → 계정 이벤트 → 경보), apps/api `AccountJourneyIntegrationTest` `a replayed refresh token reaches the account event stream…`, `sixteen threads presenting the same token…`, `SessionBodyDeliveryWebTest` `refresh is public, rotates…`, `JdbcSessionStoreDbTest` (두 DB) |
| **세션 고정** | 세션 id · 리프레시 토큰은 항상 서버가 새로 만든다(클라이언트가 정하는 값 없음), 로그인마다 새 세션. 비밀번호 재설정 · 변경 · 이메일 변경 · 수단 해제는 세션을 닫는다 | apps/api `AccountJourneyIntegrationTest` `a password reset kills every refresh token for real…` · `a password change signs the other devices out…` · `an email change confirmation signs every session out…` (진짜 `SessionRevokerAdapter` + JDBC 저장소), `SessionBodyDeliveryWebTest` `login carries a refresh token, a session id…`, `PasswordServiceTest` `reset replaces the password, signs every session out…`, `EmailChangeServiceTest` `confirming switches the address… signs everyone out…` |
| **CSRF** | 기본 body 전달은 쿠키가 없다. cookie 모드는 HttpOnly · Secure · SameSite=Strict · `Path=/api/v1/auth` + 커스텀 헤더 요구. 액세스 토큰은 `Authorization` 헤더 | `SessionCookieDeliveryWebTest` (헤더 없으면 403, body 토큰 무시, 쿠키 속성) |
| **정지 · 삭제 뒤 토큰** | 새로고침 즉시 거부, 세션 즉시 철회. **이미 낸 액세스 토큰은 만료(≤15분)까지 산다** — 알려진 한계 | `SessionBodyDeliveryWebTest` `a suspended or deleted account cannot refresh`, `DeletionAndAdminTest` |
| **이메일 변경 탈취** | 새 주소 확인 전엔 불변 · 비밀번호(없는 계정은 메일함 확인 링크) 요구 · 옛 주소에 요청 · 변경 알림(새 주소가 쓰이는 중이어도 똑같이) · **비밀번호를 바꾸면 열린 변경 링크가 죽는다** · 변경 시 세션 종료 · 계정당 한도 | `EmailChangeServiceTest`, `ReauthTest` |
| **사전 탈취 (미확인 가입 비밀번호)** | 메일함이 다른 길(매직 링크 · 확인된 소셜)로 증명되면 미확인 비밀번호를 버리고 세션 · 링크를 닫는다. 인증 링크는 발급 때의 비밀번호에 묶인다 | `SignInServiceTest` `pre-hijack - …` · `a verification link activates only the password it was issued for` |
| **유사 주소 (악센트 · 대소문자)** | 정규화 한 곳 + 글자 그대로 비교 + MySQL `utf8mb4_bin` | `EmailNormalizationTest`, `EmailExactMatchDbTest` (두 DB) |
| **소셜 연결 탈취 (로그인 CSRF)** | 연결은 다시 인증(현재 비밀번호 · 메일함 링크) + 계정에 알림 메일 + 프론트가 OAuth `state` 를 확인해야 한다 (계약 문서 §7) | `AccountSocialWebTest` `linking needs the current password…` · `an account without a password links only after the mailbox confirmation…` |
| **소셜 병합 탈취** | 확인된 이메일만 믿고 기본은 병합하지 않는다 (위 표) | `SignInServiceTest` `a verified provider email that matches…` · `merging is opt-in…` · `a provider email that is not verified is ignored…`, `AccountSocialWebTest` |
| **비밀번호 · 토큰이 로그에** | 서비스는 안 남기고, 요청/응답 DTO · 저장 행 `toString` 은 가린다(Spring MVC 가 DEBUG/TRACE 에서 본문을 `toString` 으로 찍는다 — 실측). 기본 `LogMasker` 는 `token=` 쌍도 가린다 | `AccountResponseLevelSecurityTest` `no one-time token or password ever reaches the log…`, `MailPathLogLeakTest` (진짜 메일러 · 전달 길), `SecretsStayOutOfToStringTest` (요청 · 응답 · `SignUpCommand` · `OpenedSession` · 시드 계정) |
| **권한 상승 · 관리자 사고** | 관리자 권한은 **매 호출 저장소의 계정**(ACTIVE + 역할)으로 확인 — 낡은 토큰으로 못 한다. 첫 관리자는 확인된 이메일 + ADMIN 이 아직 없을 때만, 마지막 ADMIN 은 정지 · 회수 · 삭제 불가(저장소가 원자적으로 판정 — 동시에 서로를 정지해도 0 이 안 된다), 자기 정지 불가, 비밀번호 기본값 없음, 시드 계정은 stage · prod 에서 기동 실패 | `RegistrationServiceTest` (bootstrap), `DeletionAndAdminTest` `two administrators suspending…`, `AccountCallersTest`, `JdbcAccountRepositoryDbTest` `two administrators demoting…` (두 DB), `AccountDeployGuardTest` |
| **로그인 잠금 DoS** | 시도 전부를 세므로 공격자가 남의 식별자를 10분 동안 잠글 수 있다 — **수용한 대가** (실패만 세려면 잠김 상태를 저장해야 하고 그것이 존재 오라클이 된다). 창 · 횟수는 설정 | `LoginControlsTest` |
| **삭제 뒤 남는 데이터** | 유예 뒤 모든 `AccountErasureListener` 가 성공해야 계정 행을 지운다(하나라도 실패하면 다음 주기에 재시도) | `DeletionAndAdminTest` `purge waits for the grace…` · `a failing listener keeps the account…`, `JdbcErasureDbTest`, `NotificationInboxErasureDbTest`, `SessionMaintenanceTest` · `JdbcSessionStoreDbTest` `erasing an account deletes its sessions…` (세션 행 — IP · UA — 도 지운다). 감사 표에는 계정 id · IP 가 `audit.enabled` 일 때 남는다 — 보존 기간은 앱이 정한다 |

## 삭제 · 지우기 · 내보내기

- 삭제 요청: 비밀번호(없으면 메일 링크)로 다시 인증 → `DELETED`(로그인 불가 · 세션 종료 · 존재하지 않는 계정처럼 보임) → `deletion.grace`(기본 30일) 동안 관리자가 `restore` 가능 → 주기 실행(`purge-interval`, `job-queue-jdbc` 가 있으면 잡 `account-purge`)이 지운다.
- 지울 때 `AccountErasureListener`(platform 의 공용 계약)가 모두 불린다. 구현: **board** (글 · 댓글 작성자와 반응의 계정을 `deleted:<해시>` 톰스톤으로 — 행 · 카운터는 남고 응답에 `authorDeleted: true`), **notification-jdbc** (받은편지함 줄 삭제 + 다른 수신자 줄 안의 계정 id 를 톰스톤으로). 모듈마다 자기 데이터를 지우고 · 모든 고리는 **멱등**이어야 한다.
- **한계**: 다른 사람의 알림 본문에 삭제된 사람의 글 내용이 있으면 그것은 알림을 만든 모듈의 몫이다. `storage` 의 업로드는 이 구현에 없다 (앱이 고리를 구현한다).
- **데이터 내보내기**: `AccountDataExporter` 인터페이스만 있다 (엔드포인트 · 기본 구현 없음 — 법적 요구에 맞춰 앱이 구현).

## 메일

템플릿 ko · en (`AccountMailTemplates` 빈으로 교체), 계정 로케일로 고르고 없으면 `mail.default-locale`. 링크 앞부분은 `mail.link-base-url`. 전달 길은 `notification-mail` 의 `MailSender`; 없으면 `LogOnlyMailTransport` 가 **보내지 않고** 알린다. 링크(토큰)는 앱이 `mail.log-links` 를 켠 로컬에서만 로그에 나온다 (보호 환경에서 켜면 DeployGuard 문제). 토큰은 어떤 이벤트 · 로그 줄에도 없다.
로컬 개발은 compose 의 `mail` 프로필(mailpit, `localhost:8025`)이 받는다 — `apps/sample` 이 그렇게 쓴다.

## 이벤트 · 감사 · 경보

모든 인증 사건(가입 · 확인 · 로그인 성공/실패/제한 · 비밀번호 · 세션 · 연결/해제 · 정지 · 역할 · 삭제 · 지움)은 `AccountEventPublisher` → `AccountEventListener` 빈들로 간다 (기본: 한 줄 로그, 이메일 · 토큰 없음). `skeleton.account.audit.enabled=true` + `account-jdbc` 면 `skeleton_account_audit` 표에도 쓴다. `alert` 모듈이 있으면 로그인 시도 폭주 · 리프레시 토큰 재사용이 주인 경보로 간다.

## 배포 가드 (`skeleton.env=stage|prod` 또는 auth 의 보호 프로필)

기동이 막히는 것: `skeleton.web.client-ip.mode` 미설정 · 메모리 계정 · 토큰 저장소 · 메모리 세션 저장소 · 메일 발송 길 없음 · `mail.link-base-url` 비어 있음 · `mail.log-links=ON` · 시드 계정 · 캡차를 필수로 했는데 검증기 없음 · 쿠키 전달인데 `cookie.secure=false` · 잘못된 부트스트랩 이메일. 경고: 가입이 열려 있는데 캡차 없음 · 로그인 제한 끔 · 이메일 확인 끔.

## HTTP 계약

프론트가 쓰는 계약 전체(엔드포인트 · 요청/응답 JSON · 오류 코드 · 순서)는 [account-http-contract.md](account-http-contract.md).

## 정해 둔 것 (주인이 뒤집을 수 있는 것)

- **bcrypt(기본) vs argon2**: 추가 의존이 없고 해시가 메모리를 쓰지 않아 로그인 폭주 때 메모리 DoS 면이 작다. argon2id 는 opt-in(`password.encoder=argon2` + BouncyCastle). 델리게이팅 인코더라 `{bcrypt}` 접두사로 저장되고 옛 접두사 없는 bcrypt 도 검증되며 로그인 때 새 방식으로 올라간다.
- **리프레시 전달 기본 body**: 쿠키가 없어 CSRF 면이 없고 react `api-client` 가 바로 쓴다. 대가는 XSS 가 토큰을 읽을 수 있다는 것 — 회전 · 재사용 탐지 · 짧은 액세스 토큰이 피해를 줄인다. 쿠키가 더 낫다고 판단하면 `delivery: COOKIE`.
- **로그인 한도는 시도 전부**를 센다 (위 위협 표).
- **뒤로 넘기는 일(메일 · 재설정 조회)은 프로세스 안의 작은 풀**이다 — 종료 때는 기다려 마치고 넘치면 부른 스레드가 하지만, 비정상 종료로 처리 못 한 메일은 사라지고 사용자가 다시 요청한다(재전송). **가입 계정 행은 요청 스레드가 저장한다**(202 를 받은 가입은 사라지지 않는다). 내구성이 더 필요하면 `AccountTaskRunner` 빈으로 잡 큐에 넣는다.
- **액세스 토큰은 상태 없는 JWT** — 정지 · 역할 변경은 일반 API 에는 최대 15분 늦게 반영된다 (새로고침은 즉시 현재 역할). **관리자 API 는 매 호출 저장소를 다시 읽는다.**
- **`username` = 이메일** (이 모듈이 만든 계정에는 따로 사용자 이름이 없다).
