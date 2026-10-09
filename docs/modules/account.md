# account

계정 수명주기 전체를 캡슐화한 모듈: **계정 · 로그인 수단(identities) · 역할**, 이메일+비밀번호 **가입 · 이메일 코드 확인**(가입 시도에 묶인 6자리 코드 — 확인이 곧 로그인), **비밀번호 재설정 · 변경**, **이메일 변경**(새 주소로 간 6자리 코드를 요청한 세션에서 입력한 뒤에만 바뀐다),
**소셜 연결 · 해제**, **삭제**(다시 인증 → 유예 → 지우기, 다른 모듈의 데이터는 `AccountErasureListener` 로), **로그인 시도 제한 · 캡차 고리 · 계정 이벤트 · 경보**, **운영자 도구**(정지 · 역할 · 첫 관리자)와 HTTP.
`auth` 의 `AuthAccountRepository` 포트를 이 모듈이 진짜 계정으로 구현하므로 `auth` 는 그대로(의존성 한 줄) 실제 계정으로 로그인한다.
저장은 어댑터 `account-jdbc`(PostgreSQL · MySQL)가 한다 — 이 모듈의 메모리 저장소는 로컬 · 시험용이다(stage · prod 가드가 막는다).

**로그인 수단 추상화**: 계정에는 수단이 없고 `identities` 행(method 문자열 코드 + subject)이 붙는다. 이메일+비밀번호 · 각 소셜 제공자 · 매직 링크가 같은 표의 행이다.
새 수단은 `SignInMethod` 빈 하나 + 자기 엔드포인트에서 `AccountSignInService.signIn(SignInProof)` 호출 + `AuthTokenResponseFactory.issue` — 스키마 · 이 모듈은 그대로 ([docs/accounts.md](../accounts.md) "새 로그인 수단 더하기").

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `POST /api/v1/account/sign-up` | 가입 시도 — **늘 202 + `signUpId` + `expiresAt` · `resendAvailableAt`**(카운트다운용 — 있는 주소여도 · 메일이 안 나가도 같은 응답, 계정은 아직 없다) | 공개 |
| `POST /api/v1/account/verification/resend` · `POST /api/v1/auth/verify-email` | 같은 시도의 새 코드(만료된 시도도 되살린다 · 응답에 `expiresAt` · `resendAvailableAt`) · `{signUpId, code}` 로 확인 — 계정을 만들고 **로그인(토큰)** | 공개 |
| `POST /api/v1/account/login-id/availability` · `POST /api/v1/account/login-id/forgot` | 로그인 아이디(`login-id.enabled=true` 일 때만 — 꺼지면 경로가 없다) 실시간 중복 확인(늘 200, `no-store`, IP 한도) · 아이디 찾기 메일(늘 202) | 공개 |
| `POST /api/v1/account/password/forgot` · `…/reset` · `GET …/policy` | 재설정 메일(늘 202) · 새 비밀번호(모든 세션 종료) · 정책 힌트 | 공개 |
| `POST /api/v1/account/password/change` | 현재 비밀번호 필요(없는 계정은 첫 비밀번호 설정). 다른 세션 종료 | 로그인 |
| `POST /api/v1/account/login-id` | 아이디 정하기(한 번, 바꾸기 없음) — 이메일 없이도 된다. 아이디 게이트(`403 ACCOUNT.LOGIN_ID_REQUIRED`)가 풀리는 길 (`login-id.enabled=true` 일 때만) | 로그인 |
| `POST /api/v1/account/email/change` · `POST /api/v1/account/email/change/confirm` | 새 주소로 코드(202) · 같은 세션에서 코드 입력하면 바뀜(옛 주소에 알림) | 로그인 |
| `POST /api/v1/account/contact-email` · `…/confirm` · `DELETE …` | 연락받을 이메일(계정 이메일과 별개, 로그인 · 복구 수단 아님) 등록 코드 · 확인 · 삭제 (`skeleton.account.contact-email.enabled=true` 일 때만 — docs/accounts.md "연락 이메일") | 로그인 |
| `GET` · `PATCH /api/v1/account/me` | 프로필(닉네임 · 꼬리표 `displayTag` · 로케일 · 시간대 · 켠 앱에서만 `loginId` · `emailRequired` · `emailRequiredToWrite`) · 로그인 수단 목록. 닉네임이 겹치면 `409 ACCOUNT.DISPLAY_NAME_TAKEN` (`uniqueness=UNIQUE`) | 로그인 |
| `GET /api/v1/account/identities` · `DELETE …/{id}` · `POST …/social/{provider}` | 수단 목록 · 해제(다시 인증, 마지막은 409) · 소셜 연결(`auth-social` 이 있을 때) | 로그인 |
| `POST /api/v1/account/reauth/confirmation` | 비밀번호 없는 계정의 다시 인증 코드(6자리, 이 세션에서만) | 로그인 |
| `POST /api/v1/account/delete/confirmation` · `POST /api/v1/account/delete` | 삭제 확인 코드(비밀번호 없는 계정) · 삭제 예약(202, 다시 인증). 정지된 계정은 403 | 로그인 |
| `POST /api/v1/account/delete/cancel` | 탈퇴 대기 중 로그인 성공 뒤 받은 취소 토큰으로 탈퇴 취소 → 로그인 응답 (`deletion.self-restore=true` 일 때) | 공개 |
| `/api/v1/admin/accounts` … | 목록(켠 앱은 `loginId` 칸 + `?loginId=` 정확히 일치 검색 · 지워진 계정은 `status=ERASED` 로만) · 정지 · 복구 · 역할 부여/회수 · 정지 계정 즉시 지우기(`/{id}/erase`) · 재가입 차단 목록 · 해제(`/blocks`) (`skeleton.account.admin.enabled=true`) | 관리자 역할 |

**닉네임**(`skeleton.account.display-name.*`): `uniqueness`(`NONE` 기본 · `UNIQUE` · `TAGGED` 꼬리표) · `required-on-sign-up` · `fallback`(`GENERATED` → `user-1a2b3c`) · `reserved`. 규칙(`DisplayNameRules` — 같은 타입의 빈으로 **교체 가능**, 기본 `DefaultDisplayNameRules`: 1..60자 · 보이지 않는 문자(이모지의 ZWJ · 변형 선택자만 허용) · `#` `@` · `deleted:` 거부 · NFKC+소문자+보이지 않는 글자 무시 비교 키)은 가입 · 프로필 수정 · 제공자 이름이 모두 거친다. `change-limit`(기본 10/일)이 닉네임 변경 횟수를 묶는다.
스키마(`accounts.display_name_key` · `display_tag` · 유니크 `(키, 꼬리표)`)는 방식과 무관하다 — 방식을 바꾸는 법 · 탈퇴 때의 풀림: [docs/accounts.md](../accounts.md) "닉네임".
**작성자 이름**: 기본 구현 `AccountAuthorDirectory`(platform 의 `AuthorDirectory`, `@ConditionalOnMissingBean`)가 닉네임 · 꼬리표를 쿼리 한 번으로 board 같은 모듈에 낸다 — board 는 이 모듈을 모르고 이 모듈도 board 를 모른다. 앱이 같은 타입의 빈을 두면 물러난다(돌판마다 다른 닉네임 등).

**로그인 아이디**(`skeleton.account.login-id.*`, 기본 꺼짐): 이메일이 바뀌어도 안 바뀌는 **비공개** 간판. 켜면 전원이 갖는다(가입 양식 · 소셜 첫 로그인 뒤 게이트). 로그인은 **아이디 또는 이메일** + 비밀번호(`auth` 는 그대로 — `username` 에 `@` 가 없으면 아이디), 한 번 정하면 못 바꾼다, 탈퇴해도 **다시 쓸 수 없다**(원문은 지우고 `blocks.secret` 으로 키를 준 HMAC 해시만 `account_used_login_ids` 에 둔다 — 그래서 보호 환경에서 켠 앱은 `<P>_ACCOUNT_BLOCKS_SECRET` 이 없으면 **기동 실패**). 아이디가 없는 계정은 허용 목록 밖 모든 API 에서 `403 ACCOUNT.LOGIN_ID_REQUIRED`(`LoginIdRequiredInterceptor`, 이메일 게이트보다 먼저). 로그인 한도는 입력 방식마다 버킷이 따로이고(아이디는 `loginId:<입력>`) 정하기 한도는 계정별 + IP 별이다. 아이디 찾기 메일 `MailKind.LOGIN_ID_REMINDER` 는 `vars["loginId"]` 유무로 두 본문이므로 **`AccountMailTemplates` 를 바꿔 끼운 앱은 `loginId` 가 없는 경우도 처리**해야 한다. 규칙 · 위협 · 시험: [docs/accounts.md](../accounts.md) "로그인 아이디".

**이메일 필수 · 연락 이메일**: `skeleton.account.email.required=true` 면 주소 없는 계정은 허용 목록 밖 모든 API(다른 모듈 포함)에서 `403 ACCOUNT.EMAIL_REQUIRED` — `legal` 의 재동의 인터셉터와 같은 방식의 MVC 인터셉터(`EmailRequiredInterceptor`, 순서는 이쪽이 먼저, `account.http.enabled` 와 무관하게 등록)이고, 첫 이메일 등록은 **그 로그인이 연 세션**의 1시간 안에서만 재인증 없이 `email/change` 로 한다(세션 단위 — `auth-session` 의 `SessionInspector` 가 열린 시각을 읽는다). 주소 없이 쓸 수 없는 껍데기 계정은 `POST /account/discard` 로 즉시 버릴 수 있다("이메일 없는 계정" — 아이디를 정한 계정도 되고 그 아이디는 다시 못 쓴다). **`email.required-when`**: `SIGN_IN`(기본, 위의 동작) | `WRITE` — 읽기(`GET` · `HEAD` · `OPTIONS`)는 통과하고 변경 요청에서만 막는다(`me.emailRequiredToWrite`; 결제는 열지 않는다, 사소한 쓰기는 앱이 `email.extra-exclude-paths` 로 연다. 소켓으로 들어오는 쓰기 · 상태를 바꾸는 `GET` 은 이 게이트 밖). `required: false` 인데 `WRITE` 면 기동 실패. `skeleton.account.contact-email.enabled=true` 면 계정 이메일과 별개의 **연락받을 이메일** 하나(`accounts.contact_email`)를 둔다 — 서비스 알림의 받는 주소는 platform 의 `ContactAddresses`(연락 ?: 계정 이메일, 이 모듈이 `@ConditionalOnMissingBean` 으로 구현)가 푼다. 계정 · 보안 메일은 늘 계정 이메일. 규칙 · 위협 · 어디로 무엇이 가나 표: [docs/accounts.md](../accounts.md).

에러 코드는 `ACCOUNT.*` · `AUTH.*` (표: [docs/accounts.md](../accounts.md)). 계약 전체: 같은 문서의 "HTTP 계약".

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:account"))` |
| 함께 오는 모듈 | `platform`, `auth` |
| 컴파일 전용 | `notification-mail`, `captcha-turnstile`, `alert`, `idempotency`, `auth-social`, `job-queue-jdbc` |
| 설정 접두사 | `skeleton.account` — [docs/config/modules/account.yml](../config/modules/account.yml) |
| 기본 동작 | 켜짐. 가입 · 확인 · 재설정 · 변경 · 삭제 HTTP 와 로그인 시도 제한이 켜지고, 관리자 HTTP · 소셜 가입 · 병합 · 시드 계정 · 첫 관리자 · 링크 로그는 꺼져 있다. 저장은 메모리(로컬) — 운영은 `account-jdbc`. 메일 모듈이 없으면 보내지 않고 알린다. |
| 부팅에 필요한 것 | 로컬 · 시험: 없음. stage · prod(`skeleton.env` 또는 auth 의 보호 프로필): 저장소 · 토큰 저장소 · 챌린지 저장소(`account-jdbc`), 메일 발송기(`notification-mail`), `skeleton.account.mail.link-base-url`, 로그인 아이디를 켰다면 `skeleton.account.blocks.secret` — 없으면 `DeployGuard`(`account`)가 기동을 막고 시드 계정 · 링크 로그 켬도 막는다. |
| 교체 지점 | `AccountRepository`, `OneTimeTokenStore`, `ChallengeStore`, `CodeHasher`, `SocialReauthVerifier`, `PasswordPolicy`, `BreachedPasswordCheck`, `PasswordEncoder`, `AccountMailTemplates`, `AccountMailLayout`, `AccountMailTransport`, `AccountMailer`, `AccountTaskRunner`, `AccountCaptcha`, `AccountEventPublisher`, `AccountEventListener`, `SignInMethod`, `AccountTransaction`, `AccountBlockStore`, `AccountBlocks`, `AccountMaintenanceLease`, `MagicLinkIssuer`, `DisplayNameRules`, `AuthorDirectory`, `AccountCallers`, `AccountPublicController`, `AccountController`, `AdminAccountController`, `AccountDeployGuard` |
| 마이그레이션 | 없음 (스키마는 `account-jdbc` — 로그인 아이디의 `accounts.login_id` 열과 `account_used_login_ids` 표도 설정과 무관하게 생긴다, 값은 꺼진 동안 비어 있다) |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/account/src/test`, `modules/account/src/noOptionalTest` (선택 통합이 클래스패스에 없을 때) |

**메일 모양**: 13종(가입 · 이메일 변경 · 다시 인증 · 삭제 코드, 이미 가입됨, 비밀번호 재설정 · 변경, 이메일 변경 요청 · 완료 알림, 매직 링크, 삭제 예약 · 삭제 취소, 로그인 수단 추가)에 더해 **아이디 찾기 `LOGIN_ID_REMINDER`**(로그인 아이디를 켠 앱 — 아이디 유무로 두 본문, 토큰 없는 로그인 페이지 링크만; 그 밖에 연락 이메일 알림 · 이메일 등록 시도 안내도 있다)를 ko · en 으로, **text + HTML 대체 본문**(multipart)으로 보낸다. 코드 메일은 큰 고정폭 코드 블록, 링크 메일은 버튼 하나 + 원문 주소. 제목에는 코드 · 링크가 없다(로그에 남는다). 브랜드는 `skeleton.account.mail.brand.*`(서비스 이름 · 로고 https · 색 · 문의 주소 · 바닥글)로만 바꾸고, `html-enabled=false` 면 텍스트만 보낸다. 앱이 문구를 바꾸려면 `AccountMailTemplates` 빈, **틀만** 바꾸려면 `AccountMailLayout` 빈(`MailPage` 의 날것 값을 받아 스스로 이스케이프한다)을 둔다. 눈으로 보기: `./gradlew :modules:account:mailPreview` → `build/mail-preview/*.html`. 문구를 바꾸면 `UPDATE_GOLDEN=1 ./gradlew :modules:account:test --tests '*AccountMailGoldenTest'` 로 골든 파일을 다시 쓰고 diff 를 본다. 진짜 제공자 · 메일 시험: [real-provider-setup](../real-provider-setup.md).

자세히: [계정 수명주기 · 위협 모델](../accounts.md) · [auth](auth.md) · [account-jdbc](account-jdbc.md) · [auth-session](auth-session.md) · 메일 링크 로그인은 `auth-magic-link` 모듈 (모듈 색인)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
