<!-- 생성물 — capabilities.json 에서 `perl scripts/build-capabilities.pl` 이 만든다. 손으로 고치지 않는다 -->

# 기능 카탈로그 — kotlin-skeleton

Kotlin + Spring Boot 백엔드 스켈레톤 — 새 프로젝트가 필요한 모듈만 골라 한 줄로 찍어 가는 부품 모음(모듈 하나 = 의존성 한 줄). 프런트는 형제 레포 react-skeleton.

정본은 `capabilities.json`(스키마 `docs/capabilities.schema.json`)이고 이 문서 · `llms.txt` 는 거기서 만든다. 항목마다 한 줄 요약 · 켜는 법 · 자동으로 따라오는 모듈 · 설정 접두사 · HTTP 경로 · 비밀 · 짝 프런트 · 쓰지 않는 경우 · 한국어/영어 키워드가 있다.

## 읽는 법

- **무엇이 필요하다는 말을 받으면** 아래 결정표에서 그 말(키워드)을 찾아 id · 명령 조각을 고른다. 표에 없으면 「전체 목록」의 키워드 열을 훑는다. 만들기 전에 이미 있는지 먼저 본다.
- 모듈 이름 = id. `자동으로 따라온다` 는 Gradle 의존으로 닫혀 같이 오는 모듈이라 `--modules` 에 적지 않아도 된다. `함께 골라야 한다` 는 자동으로 오지 않지만 없으면 동작하지 않는 짝이다.
- 스타터(`apps/api`)에 이미 있는 모듈: `account`, `account-jdbc`, `alert`, `auth`, `auth-session`, `auth-session-jdbc`, `auth-social`, `captcha-turnstile`, `db-postgresql`, `idempotency`, `job-queue-jdbc`, `legal`, `legal-jdbc`, `migration`, `migration-flyway`, `notification-mail`, `persistence-jdbc`, `platform`, `time` — 조각에서 뺀다.
- 설정 키와 기본값은 `docs/config/modules/<모듈>.yml`, 모듈 한 쪽 문서는 `docs/modules/<모듈>.md`. 환경변수의 `<P>` 는 배포 선언의 `env_prefix`(설정 접두사의 대문자).

## new-project.sh 인터페이스

- kotlin: `scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix> [--modules a,b,c] [--db postgresql|mysql] [--with-workbench] [--with-sample] [--dry-run]`
- react: `scripts/new-project.sh <target-dir> <name> [--packages a,b,c] [--ssr] [--without-storybook] [--with-workbench] [--with-sample] [--scope @acme]`
- 조각을 합치는 법: `--modules` 는 하나로 합치고(쉼표) 다른 옵션(`--db` · `--with-sample` …)은 그대로 덧붙인다. 의존으로 닫히는 모듈은 적지 않아도 따라온다. `--dry-run` 을 붙이면 아무것도 만들지 않고 고른 모듈과 따라온 이유만 보인다.
- 완성된 예(명령 · 환경변수 · 실행 · 검증 · 배포)와 손으로 써야 하는 것: `docs/new-project-recipe.md`

## 필요한 것 → 고를 것

| 필요한 것 | 고를 것(id) | kotlin `new-project.sh` 조각 | react `new-project.sh` 조각 | 그래도 손으로 써야 하는 것 |
|---|---|---|---|---|
| 로그인 (이메일 · 비밀번호) · JWT · 로그인한 사람만 보는 API | `auth` | (덧붙일 것 없음) | (기본 포함) — react `auth` | 스타터에 기본 포함 — 회원가입 · 비밀번호 재설정 · 계정 DB 저장 · 세션은 아래 「회원가입 · 계정 관리」 행(account, 스타터에 들어 있다). 운영에는 JWT_SECRET(32바이트 이상, 배포 선언의 secrets: 에 이름). |
| 회원가입 · 이메일 인증 · 비밀번호 재설정 · 이메일 변경 · 계정 설정 · 탈퇴 · 로그인 세션 관리 | `account` + `account-jdbc` + `auth-session` + `auth-session-jdbc` | (덧붙일 것 없음) | (기본 포함) — react `auth` `account-lifecycle` | 스타터에 기본 포함(account-jdbc · auth-session-jdbc). 운영에서 서려면 메일 발송 길(`--modules notification-mail` + SMTP 환경변수), JWT_SECRET, <P>_ACCOUNT_MAIL_LINK_BASE_URL, 클라이언트 IP 모드(홈서버 플랫폼이 넣는다)가 필요하다 — 없으면 DeployGuard 가 기동을 거부한다. 소셜 가입 · 병합과 refresh 유예는 앱 yml 의 선택. 가입 · 설정 화면은 프런트. |
| 비밀번호 없이 이메일 링크로 로그인 (매직링크) | `auth-magic-link` | `--modules auth-magic-link` | (기본 포함) — react `magic-link-login` | 스타터의 account 위에 얹는다. 링크 메일을 실제로 보내려면 notification-mail. 모르는 주소의 가입까지 허용하려면 skeleton.auth-magic-link.sign-up=true(모듈 기본은 이미 있는 계정으로만). 링크 도착 화면은 프런트. |
| 관리자 회원 관리 (목록 · 정지 · 역할 · 삭제 복구) | `account` | (덧붙일 것 없음) | (기본 포함) — react `account-admin` | 스타터의 account 에 있다 — skeleton.account.admin.enabled=true 로 켜고(기본 꺼짐) 첫 관리자는 skeleton.account.bootstrap.admin-email 로 정한다(확인된 이메일 + ADMIN 이 아직 없을 때만). 관리자 권한은 매 호출 저장소의 계정으로 확인한다. 표 화면은 프런트 @skeleton/auth/admin. |
| 소셜 로그인 (구글 · 카카오 · 네이버) | `auth-social` + (`auth-social-google` \| `auth-social-kakao` \| `auth-social-naver`) | `--modules auth-social,auth-social-google` — `auth-social-google` \| `auth-social-kakao` \| `auth-social-naver` 중 하나 이상 고른다 | (기본 포함) — react `social-login` | 제공자 enabled · client-id · client-secret · redirect-uri(비밀은 환경변수). 계정 연결 저장(OAuthAccountLinkRepository)과 가입 정책(OAuthAccountProvisioningPolicy)은 앱이 구현한다 — 기본은 메모리 저장 · 이미 연결된 계정만 통과. 로그인 버튼 · /auth/callback 화면은 프런트. |
| 글로벌 소셜 로그인: LINE · X · 그 외 OIDC | `auth-social` + (`auth-social-oidc` \| `auth-social-x`) | `--modules auth-social,auth-social-google,auth-social-oidc,auth-social-x` — `auth-social-oidc` \| `auth-social-x` 중 하나 이상 고른다 | (기본 포함) — react `social-login` | LINE 은 auth-social-oidc 의 프리셋(client-id · client-secret 만), X 는 auth-social-x, Microsoft 같은 OIDC 제공자는 auth-social-oidc 의 providers.<코드> 에 issuer · client-id · client-secret. client-id 를 적은 제공자만 켜지고 GET /auth/methods 에 나온다(PKCE · nonce · 인가 URL 포함). LINE · X 는 이메일이 없거나 확인되지 않아 주소 없는 계정이 될 수 있다(docs/accounts.md). 콘솔 설정과 redirect URI 는 docs/modules/auth-social-oidc.md · auth-social-x.md. |
| 게시판 · 글쓰기 · 댓글 · 대댓글 · 공감(반응) | `board` + `board-jdbc` | `--modules board,board-jdbc` | `--packages board` — react `board` | 게시판 코드 만들기(skeleton.board.seed-boards 설정이나 운영자 API) · 반응 종류(skeleton.board.reaction.types) · 운영자 역할(MODERATOR). notification 을 같이 고르면 댓글 알림이 기본으로 켜진다. 목록 · 상세 · 글쓰기 화면은 프런트. |
| 알림 (종 · 목록 · 안 읽은 수) | `notification` + `notification-jdbc` | `--modules notification,notification-jdbc` | `--packages notifications` — react `notifications` | 알림을 만드는 쪽(NotificationPublisher.publish)은 앱 코드 — 받는 사람은 NotificationEvent.recipientIds. 프런트에서는 NotificationBell 배치와 알림 API 인스턴스. |
| 알림이 새로고침 없이 즉시 뜬다 (실시간) | `notification` + `notification-jdbc` + (`notification-sse` \| `notification-websocket`) | `--modules notification,notification-jdbc,notification-sse` — `notification-sse` \| `notification-websocket` 중 하나 이상 고른다 | `--packages notifications,realtime` — react `live-notifications` | SSE(단방향, 더 단순) 또는 WebSocket(양방향) 중 고른다. 둘 다 프로세스 안 브로커라 인스턴스가 여럿이면 다른 인스턴스의 알림을 못 받는다. 프런트 연결 훅. |
| 파일 업로드 (이미지 · 첨부) | `storage` + `storage-s3` | `--modules storage,storage-s3` | `--packages storage` — react `storage` | 버킷 · 키(환경변수), 업로드 규칙(skeleton.storage.validation — 확장자 · 크기), 업로드한 파일 키를 도메인에 저장하는 코드(앱). 로컬 개발은 compose 의 S3 를 scripts/dev.sh 가 올린다. |
| 결제 (토스 · 스트라이프) | `payment` + (`payment-toss` \| `payment-kakaopay` \| `payment-naverpay` \| `payment-stripe` \| `payment-paddle`) | `--modules payment,payment-toss` — `payment-toss` \| `payment-kakaopay` \| `payment-naverpay` \| `payment-stripe` \| `payment-paddle` 중 하나 이상 고른다 | `--packages payment` — react `payment` | HTTP 엔드포인트가 없다 — 주문 · 금액 검증 컨트롤러 · 성공/실패 리다이렉트 처리 · 웹훅 서명 검증을 앱이 쓴다(PaymentService.confirm 은 받은 금액을 그대로 넘긴다). enabled=true + secret-key(환경변수). 중복 결제 방지에는 idempotency. |
| 이벤트 (출석 · 친구초대) · 쿠폰 · 포인트 · 쿠폰으로 결제 할인 · 무료 이용권 | `campaign` + `reward` + `coupon` + `point` | `--modules campaign,campaign-jdbc,reward,reward-jdbc,coupon,coupon-jdbc,point,point-jdbc` | `--packages rewards` — react `rewards` `rewards-admin` | 보상 모듈은 서로를 모르고 각자 단독으로 돈다(쿠폰만 · 포인트만 · 이벤트 + 일꾼만). 운영 경로는 꺼져 있다(skeleton.<module>.admin.enabled). 결제 할인은 payment-jdbc 와 같이 있으면 coupon 이 할인 수단으로 서고, 친구초대의 초대자 보상을 첫 결제에 걸려면 앱의 결제 리스너 끝에서 Referrals.qualify(계정)를 부른다. 이벤트 설정(어떤 보상을 줄지)은 운영 화면 또는 Campaigns 서비스로 넣는다 - 씨앗 예: apps/sample 의 RewardsSeed. |
| 봇 방지 (캡차) | `captcha-turnstile` | `--modules captcha-turnstile` | `--packages captcha-turnstile` — react `captcha-turnstile` | 가입 · 로그인 요청에서 TurnstileVerifier 로 토큰을 검증하는 코드(앱)와 enabled=true + secret-key(환경변수). |
| 메일 보내기 (SMTP) | `notification-mail` | `--modules notification-mail` | (프런트 없음) | SMTP 환경변수(host · username · password · from)와 메일 본문 · 템플릿(앱). |
| 슬랙으로 알림 | `notification-slack` | `--modules notification-slack` | (프런트 없음) | 웹훅 URL(환경변수)과 보낼 알림 종류(앱). |
| 주인 경보 (에러 · 장애를 Discord 로) | `alert` | `--modules alert` | (프런트 없음) | 웹훅 주소(환경변수 <P>_ALERT_WEBHOOK_URL) — 없으면 로그만 남는다. 앱 고유 경보는 AlertKind 를 구현해 OwnerAlerts.emit 으로 보낸다. |
| 여러 인스턴스에서 같은 경보를 한 번만 | `alert` + `alert-jdbc` | `--modules alert,alert-jdbc` | (프런트 없음) | alert 의 설정에 더해 DB 스키마(마이그레이션이 적용된 postgres · mysql). 단일 인스턴스면 alert 만으로 충분하다. |
| 작업 큐 · 백그라운드 재시도 (Redis 없이) | `job-queue-jdbc` | `--modules job-queue-jdbc` | (프런트 없음) | JobHandler 구현(멱등이어야 한다 — 재시도된다), 작업 넣기(JobQueue), DB 마이그레이션 적용(spring.flyway.locations). |
| 정기 작업 (크론 · 스케줄러) | `scheduler` | `--modules scheduler` | (프런트 없음) | @Scheduled 작업 코드(앱). 여러 인스턴스에서 한 번만 돌리려면 redis-lock 의 락 매니저로 바꾼다. |
| 캐시 (Redis) | `redis-cache` | `--modules redis-cache` | (프런트 없음) | Redis 서버(배포 선언 redis: true), 캐시 이름 · TTL 설정, 캐시를 거는 코드(앱). Redis 가 죽어도 FAIL_OPEN 으로 원본을 읽는다. |
| 분산 락 · 중복 실행 방지 | `redis-lock` | `--modules redis-lock` | (프런트 없음) | @DistributedLock 을 거는 코드(앱)와 Redis 서버(배포 선언 redis: true). Redis 가 없으면 락에서 즉시 실패한다. |
| 요청 제한 · 도배 방지 (여러 인스턴스) | `redis-rate-limit` | `--modules redis-rate-limit` | (프런트 없음) | skeleton.web.rate-limit.enabled=true 로 켠다. 단일 인스턴스면 모듈 없이 platform 의 인메모리 저장소로 충분하다. |
| 중복 요청 방지 (멱등 키) | `idempotency` | `--modules idempotency` | (프런트 없음) | 명령 엔드포인트에 @IdempotentOperation. 저장소는 인메모리(인스턴스별) — 여러 인스턴스면 IdempotencyStore 를 앱이 구현한다. |
| 데이터 암호화 · 불투명 URL 토큰 | `crypto` | `--modules crypto` | (프런트 없음) | 키 설정(환경변수 <P>_CRYPTO_PRIMARY_KEY_ID · <P>_CRYPTO_KEYS_<ID>)과 암호화할 필드 선택(앱). |
| @Async · 백그라운드 작업에서 trace id 유지 | `async` | `--modules async` | (프런트 없음) | 스레드 풀 크기 설정과 @Async 를 거는 코드(앱). |
| Kafka 로 이벤트 발행 | `event-kafka` | `--modules event-kafka` | (프런트 없음) | 브로커 주소 · KafkaOperations 빈 · enabled=true. 소비(consumer)는 없다. |
| MySQL 로 쓰기 (기본은 PostgreSQL) | `db-mysql` | `--db mysql` | (프런트 없음) | --modules 가 아니라 --db mysql 로 고른다. --with-sample · --with-workbench 와 함께 쓸 수 없다(PostgreSQL 전용). |
| JPA 또는 jOOQ 로 DB 접근 (기본은 Data JDBC) | (백엔드 모듈 없음) + (`persistence-jpa` \| `persistence-jooq`) | `--modules persistence-jpa` — `persistence-jpa` \| `persistence-jooq` 중 하나 이상 고른다 | (프런트 없음) | 하나만 고른다 — JDBC · JPA · jOOQ 를 병행하지 않는다. jOOQ 는 DDL 파일에서 코드를 생성한다(docs/persistence-jooq.md). |
| 에러 응답 · 표준 응답 · 요청 로깅 · OpenAPI · 외부 API 호출 | `platform` | (덧붙일 것 없음) | (기본 포함) — react `api-client` | 스타터에 기본 포함 — 따로 켤 것이 없다. 컨트롤러는 DataResponse/ApiError 규칙을 쓴다(docs/errors.md · docs/openapi.md). |
| 시간대 · 날짜 표시 · 예약 시각 (글로벌) | `time` | (덧붙일 것 없음) | (기본 포함) — react `time` | 스타터에 기본 포함. Instant · LocalDate · ZonedMoment 를 구분한다 — ZoneId.systemDefault() · TIMESTAMP 칼럼은 쓰지 않는다(docs/time.md). |
| 참조 앱을 같이 가져가서 보고 따라 하기 | `app-sample` | `--with-sample` | `--with-sample` — react `app-sample` | 샘플은 참조다 — 쓰지 않을 기능은 지운다. 짝 프런트는 react-skeleton 의 apps/sample. PostgreSQL 전용. |
| 모든 모듈을 한 번에 눌러 보는 데모 | `app-workbench` | `--with-workbench` | `--with-workbench` — react `app-workbench` | 복사 대상이 아니다 — 모듈을 눈으로 확인하는 용도. PostgreSQL 전용. |
| 다국어 (한국어 · 영어 전환) | (백엔드 모듈 없음) | (덧붙일 것 없음) | `--packages i18n` — react `i18n` | 백엔드 모듈은 필요 없다 — 문구 사전은 프런트. 서버가 뷰어 로케일 · 시간대를 알아야 하면 time(스타터에 기본 포함). |
| 랜딩 · 요금제 · 약관 · 쿠키 동의 · 404 페이지 | (백엔드 모듈 없음) | (덧붙일 것 없음) | `--packages marketing` — react `landing-page` `pricing-page` `legal-documents` `cookie-consent` `error-pages` | 백엔드는 할 일이 없다(정적 공개 화면). 요금제 선택 이후의 결제 연결은 payment 행. |
| 검색 노출 · 링크 미리보기 · 서버 렌더링 | (백엔드 모듈 없음) | (덧붙일 것 없음) | `--packages seo --ssr` — react `seo` `app-starter-ssr` | 백엔드는 할 일이 없다. SSR 앱은 배포 선언이 따로 필요하다 — deploy/app.yaml 의 web: 은 정적 파일 서빙뿐(docs/deploy.md §8). |
| 다크 모드 · 디자인 토큰 · 화면 부품 · 대시보드/목록/폼 화면 틀 | (백엔드 모듈 없음) | (덧붙일 것 없음) | (기본 포함) — react `theme` `tokens` `ui` `screen-patterns` | 백엔드는 할 일이 없다 — react-skeleton 에 기본 포함. |
| 이용약관 · 개인정보 처리방침 · 가입 동의 기록 · 새 판 재동의 · 마케팅 수신 동의(선택, 철회) | `legal` + `legal-jdbc` | (덧붙일 것 없음) | (기본 포함) — react `legal` | 스타터에 기본 포함(legal-jdbc) — 문서는 모듈의 TEMPLATE(ko/en)이라 stage · prod 에서는 기동이 거부된다: src/main/resources/legal/ 에 자기 문서와 manifest.json 을 두고(검토 끝난 판은 REVIEWED + sha256), skeleton.legal.facts.* 를 채운다. 시험 배포는 <P>_LEGAL_ACKNOWLEDGE_TEMPLATE=true. MySQL 은 log_bin_trust_function_creators=1. 동의 화면 · 가입 폼 체크박스는 프런트(docs/legal-http-contract.md). |
| 운영자 역할을 화면에서 만들고 권한을 고른다 (예: CS 담당자는 결제 보기 + 환불만) | `authz` + `authz-jdbc` | `--modules authz,authz-jdbc` | (기본 포함) — react `account-admin` | 켜기 skeleton.authz.admin.enabled=true(기본 꺼짐 → 404). 없으면 역할 → 권한은 skeleton.authz.roles(yml)로 고정. 첫 기동에 yml 매핑이 표로 한 번 옮겨지고 그 뒤는 표가 진실이다. 계정에 역할을 주는 건 account 의 관리자 API. 역할 표 화면은 프런트 @skeleton/auth/admin. |

## 전체 목록

### 모듈 (`modules/*`)

| id | 요약 | 켜는 조각 | 키워드 (한국어 / 영어) | 짝 프런트 |
|---|---|---|---|---|
| `account` | 계정 수명주기 — 이메일 · 비밀번호 가입과 이메일 확인, 비밀번호 재설정 · 변경, 이메일 변경, 소셜 연결(자동 병합 없음), 탈퇴(다시 인증 → 유예 → 개인정보만 지우고 행은 ERASED 로 남김, 탈퇴 취소, 정지는 박제 + 해시 재가입 차단), 닉네임(중복 허용 · 유일 · 꼬리표 `닉네임#0417` 방식 · 필수 · 자동 닉네임 · 예약어, 작성자 이름 조회 AuthorDirectory 기본 구현), 이메일 필수(주소 없는 소셜 계정은 등록 전까지 모든 API 403 ACCOUNT.EMAIL_REQUIRED · 첫 등록은 재인증 없이, 기본 꺼짐 · `required-when=WRITE` 면 읽기는 통과하고 쓰기에서만 막는다), 로그인 아이디(이메일이 바뀌어도 안 바뀌는 비공개 간판 — 아이디 또는 이메일로 로그인 · 실시간 중복 확인 POST /account/login-id/availability · 아이디 정하기 게이트 POST /account/login-id · 아이디 찾기 메일 POST /account/login-id/forgot · 탈퇴해도 재사용 금지(해시), 기본 꺼짐), 연락받을 이메일(계정 이메일과 별개 · 복구 수단 아님 · 서비스 알림 수신 주소 SPI, 기본 꺼짐), 로그인 시도 제한, 관리자 도구, 첫 관리자, ko/en 메일 템플릿. auth 가 진짜 계정으로 로그인하게 한다. | 스타터(apps/api)에 기본 포함 | 회원가입, 가입, 이메일 인증, 비밀번호 재설정, 비밀번호 찾기, 이메일 변경, 계정 삭제, 탈퇴, 계정 관리, 로그인 아이디, 아이디 찾기, 관리자 도구, 계정 정지, 프로필 / sign up, registration, email verification, password reset, forgot password, change email, delete account, account management, login id, username login, find my id, admin tools, suspend account, profile | `@skeleton/auth` |
| `account-jdbc` | account 의 저장소 · 토큰 · 감사 기록을 PostgreSQL · MySQL 로 — 스키마는 모듈 마이그레이션(MySQL 은 이메일 · 닉네임 키를 utf8mb4_bin 으로 정확 일치, 닉네임 방식 셋을 유니크 (키, 꼬리표) 하나로). 보호 환경에서 메모리 저장소를 막는 가드를 통과하는 방법. | 스타터(apps/api)에 기본 포함 | 계정 저장, 계정 DB, 회원 테이블, 계정 마이그레이션 / account storage, accounts table, account persistence, account migration | `@skeleton/auth` |
| `alert` | 주인 경보 — 5xx 몰림 · 기동 실패 · 죽은 작업을 Discord 호환 웹훅(+ 선택적 메일)으로 알린다. 에러 수집(Sentry)의 답. | 스타터(apps/api)에 기본 포함 | 경보, 주인 알림, 에러 알림, 장애 알림, 디스코드 알림, 에러 수집, 5xx 알림 / owner alert, error reporting, discord webhook, incident alert, 5xx alert, sentry alternative | — |
| `alert-jdbc` | 경보 기록을 DB 에 두어 여러 인스턴스 · 재시작을 가로질러 같은 경보를 접는다 (PostgreSQL · MySQL). | `--modules alert-jdbc` | 경보 기록, 경보 중복 접기, 경보 DB / alert ledger, alert dedupe, alert database | — |
| `api-key` | API 키(B2B 호출자용 장기 자격) — 사용자가 이름 · 범위 · 만료를 골라 발급(다시 인증)하고 본인이 회수하며, 키로 온 요청은 `@ApiKeyScope` 로 표시한 경로에 한해(기본 거부) 주인 계정으로 움직인다. 키별 한도 · 활성 키 상한 · 만료 상한은 앱이 정한다. 저장은 해시뿐. | `--modules api-key,api-key-jdbc` | API 키, 액세스 토큰, 개인 액세스 토큰, B2B API, 키 발급, 범위, 키 회수 / api key, personal access token, scoped key, api token, key revocation, per-key rate limit | — |
| `api-key-jdbc` | API 키를 DB 에 둔다 (PostgreSQL · MySQL) — 토큰 해시만 저장하고, 회수 · 마지막 사용(1분 해상도)은 한 문장의 조건부 갱신이라 동시에 몰려도 한 번만 쓴다. | `--modules api-key-jdbc` | API 키 저장소, API 키 DB / api key storage, api key repository | — |
| `async` | @Async · CompletableFuture 작업이 호출 스레드의 trace id · MDC · 보안 컨텍스트를 이어받는 실행기와 작업 그룹. | `--modules async` | 비동기, 백그라운드 작업, 스레드 풀, @Async, 작업 그룹 / async, background task, thread pool, mdc propagation, completable future | — |
| `async-notification` | 비동기 작업에서 처리되지 않은 예외를 알림(notification)으로 흘려 보낸다. | `--modules async-notification` | 비동기 예외 알림, 백그라운드 오류 알림, 비동기 에러 / async exception alert, background error notification, uncaught async exception | — |
| `auth` | 로그인 — 비밀번호 로그인 · JWT 발급/검증 · 로컬 dev-login · 비상용 break-glass · SecurityFilterChain (계정 저장소는 앱이 정한다). | 스타터(apps/api)에 기본 포함 | 로그인, 로그아웃, 인증, 토큰, JWT, 권한, 관리자 로그인, 비상 접근 / login, authentication, jwt, token, dev login, break glass, security | `@skeleton/auth` |
| `auth-magic-link` | 이메일로 받은 한 번 쓰는 링크로 로그인(비밀번호 없이) — 이미 있는 계정으로 들어오고(skeleton.auth-magic-link.sign-up 으로 가입도 허용), 미확인 계정에서는 가입 때의 미확인 비밀번호를 버린다. | `--modules auth-magic-link` | 매직링크, 링크 로그인, 비밀번호 없는 로그인, 이메일 로그인 / magic link, passwordless, email sign in, link login | `@skeleton/auth` |
| `auth-session` | 로그인 세션 — 불투명한 리프레시 토큰(해시 저장 · 매번 회전 · 재사용 탐지 + 이벤트), 세션 목록 · 개별 로그아웃, body 전달(기본) 또는 HttpOnly 쿠키(CSRF 헤더). 끝난 세션 주기 청소와 계정 삭제 때 세션 행 삭제. | 스타터(apps/api)에 기본 포함 | 세션, 리프레시 토큰, 토큰 갱신, 로그인 유지, 기기 목록, 다른 기기 로그아웃 / session, refresh token, token rotation, keep signed in, device list, sign out everywhere | `@skeleton/auth` |
| `auth-session-jdbc` | auth-session 의 세션 · 리프레시 토큰 저장을 PostgreSQL · MySQL 로 — 한 토큰은 조건부 UPDATE 한 문장으로 한 번만 쓰이고, 인스턴스끼리 세션을 나눈다. | 스타터(apps/api)에 기본 포함 | 세션 저장, 세션 DB, 리프레시 토큰 저장 / session storage, session table, refresh token storage | `@skeleton/auth` |
| `auth-social` | 소셜 로그인의 제공자 중립 계약 — POST /api/v1/auth/social/{provider}/login 라우팅 · 계정 연결 · 가입 정책 (제공자는 google · kakao · naver 모듈). | 스타터(apps/api)에 기본 포함 | 소셜 로그인, 간편 로그인, 구글 로그인, 카카오 로그인, 네이버 로그인, OAuth, 계정 연결 / social login, oauth, sign in with google, kakao login, naver login, account linking | `@skeleton/auth` |
| `auth-social-google` | 구글 OAuth 제공자 — 토큰 교환 · 프로필 조회 클라이언트를 소셜 로그인의 제공자로 등록한다(클라이언트 id · secret 만 있으면 된다). | `--modules auth-social-google` | 구글 로그인, 구글 소셜 로그인, 소셜 로그인 / google login, google oauth, social login | `@skeleton/auth` |
| `auth-social-kakao` | 카카오 OAuth 제공자 — 토큰 교환 · 프로필 조회 클라이언트를 소셜 로그인의 제공자로 등록한다(클라이언트 id · secret 만 있으면 된다). | `--modules auth-social-kakao` | 카카오 로그인, 카카오 소셜 로그인, 소셜 로그인 / kakao login, kakao oauth, social login | `@skeleton/auth` |
| `auth-social-naver` | 네이버 OAuth 제공자 — 토큰 교환 · 프로필 조회 클라이언트를 소셜 로그인의 제공자로 등록한다(클라이언트 id · secret 만 있으면 된다). | `--modules auth-social-naver` | 네이버 로그인, 네이버 소셜 로그인, 소셜 로그인 / naver login, naver oauth, social login | `@skeleton/auth` |
| `auth-social-oidc` | 범용 OpenID Connect 소셜 로그인 제공자 — 속성만으로 제공자를 여러 개 더한다(discovery · JWKS 키 교체 · ID 토큰 검증 · PKCE · nonce · LINE 프리셋). client-id 를 적은 제공자만 켜진다. | `--modules auth-social-oidc` | 라인 로그인, LINE 로그인, OIDC, OpenID Connect, 글로벌 소셜 로그인, 마이크로소프트 로그인, 소셜 로그인 / line login, openid connect, oidc, microsoft login, social login, global social login | `@skeleton/auth` |
| `auth-social-x` | X(Twitter) 로그인 제공자 — OAuth 2.0 Authorization Code + PKCE(필수), 기밀 클라이언트(Basic), GET /2/users/me. client-id 를 적으면 켜진다. | `--modules auth-social-x` | X 로그인, 엑스 로그인, 트위터 로그인, 글로벌 소셜 로그인, 소셜 로그인 / x login, twitter login, sign in with x, social login, global social login | `@skeleton/auth` |
| `author-profile` | 작성자 프로필 — 한 계정이 닉네임(프로필)을 여러 개 만들어 글 · 댓글마다 골라 쓰고, 두 프로필이 같은 사람이라는 사실이 다른 사용자에게 드러나지 않게 한다(공개 작성자 id prf_…, 독립 꼬리표, 즉시 삭제, 옛 글을 첫 프로필로 넘겨받기). 도메인 · 서비스 · 디렉터리 · 신원 확인 · 탈퇴 고리 · 내 프로필 HTTP(/api/v1/author-profiles: config · 목록 · 만들기 · 이름 바꾸기 · 지우기)까지이고 저장소는 author-profile-jdbc 가 맡는다. | `--modules author-profile,author-profile-jdbc` | 작성자 프로필, 닉네임 여러 개, 부캐, 익명 프로필, 프로필 선택, 글마다 닉네임 / author profile, multiple nicknames, pseudonym, alias, persona, unlinkable identities | `@skeleton/author-profile` |
| `author-profile-jdbc` | 작성자 프로필 저장소 — PostgreSQL · MySQL 로 author-profile 의 저장 포트를 구현한다(author_profiles 표 · (이름 키, 꼬리표)와 첫 프로필 유니크 · 첫 프로필 줄을 잠그는 원자적 상한). | `--modules author-profile-jdbc` | 작성자 프로필 저장소, 프로필 DB / author profile storage, author profile repository, profile database | `@skeleton/author-profile` |
| `authz` | 운영자 역할 관리 — 역할 만들기 · 고치기 · 지우기(역할 → 권한 묶음)와 붙은 모듈이 선언한 권한 카탈로그 API(/api/v1/admin/roles · /api/v1/admin/permissions). 안전장치: 전권 역할 고정 · 자기에게 없는 권한은 못 줌(403) · 쓰는 계정이 있는 역할은 못 지움 · 자기 잠금 막기. 역할 변경은 OperatorAuditLog 계약으로만 알린다(기본 아무것도 안 함). 기본 꺼짐(skeleton.authz.admin.enabled). | `--modules authz,authz-jdbc` | 역할 관리, 권한 관리, 역할 만들기, 권한 묶음, 운영자 권한, CS 담당자 역할, 환불만 허용 / role management, permissions, role based access, admin roles, least privilege, custom role | `@skeleton/auth` |
| `authz-jdbc` | 역할 → 권한 표 저장소 — PostgreSQL · MySQL 로 authz 의 저장 포트를 구현하고(authz_roles · authz_role_permissions, 전권 역할은 표를 못 바꾼다) 판정이 읽는 역할 → 권한 매핑을 yml 에서 이 표로 바꾼다(캐시: 같은 인스턴스는 즉시, 다른 인스턴스는 skeleton.authz.cache-ttl). 첫 기동에 yml 매핑을 한 번만 옮긴다. | `--modules authz-jdbc` | 역할 권한 저장소, 권한 표 / role permission storage, authz database | `@skeleton/auth` |
| `badge` | 배지 — 앱이 정의하는 업적 배지의 정의 · 수여/회수(사유 필수) · 내 배지 · 프로필 배지(비로그인 읽기는 앱이 켠다) · 보상 지급기 `badge`. 포인트 · 쿠폰 · 이벤트를 모르고 조립으로만 같이 준다. | `--modules badge,badge-jdbc` | 배지, 업적, 뱃지, 프로필 배지, 수동 수여, 회수 / badge, achievement, profile badge, award badge, revoke badge | `@skeleton/rewards` |
| `badge-jdbc` | 배지 정의 · 보유 · 수여/회수 내역을 DB 에 둔다 (PostgreSQL · MySQL) — 동시 수여는 보유 줄 유니크, 지급 건 재시도는 내역 유니크로 한 번. | `--modules badge-jdbc` | 배지 저장소, 배지 DB / badge storage, badge repository | `@skeleton/rewards` |
| `board` | 게시판 — 글 · 중첩 댓글(대댓글) · 설정으로 늘리는 반응(좋아요 · 공감 …) · 운영자 숨김/고정 · 댓글 알림 · 작성자 닉네임(authorName · authorTag, AuthorDirectory 로 요청당 한 번 조회), HTTP /api/v1/boards 까지. | `--modules board,board-jdbc` | 게시판, 커뮤니티, 글쓰기, 댓글, 대댓글, 공감, 좋아요, 반응, 운영자 숨김, 공지 고정 / board, forum, community, post, comment, reply, reaction, like, moderation | `@skeleton/board` |
| `board-jdbc` | 게시판 저장소 — PostgreSQL · MySQL 로 board 의 저장 포트 네 개를 구현한다(boards · board_* 테이블 · 원자적 카운터). | `--modules board-jdbc` | 게시판 저장소, 게시판 DB / board storage, board repository, board database | `@skeleton/board` |
| `campaign` | 이벤트 — 출석(매일 · 누적 · 연속 달성) · 친구초대(양쪽 보상) · 업적(앱이 보고한 수치가 단계에 닿으면)을 판정해 보상 지급 건으로 기록하고, 운영자가 이벤트를 만들고 켜고 끈다. | `--modules campaign,campaign-jdbc,reward-jdbc` | 이벤트, 출석 체크, 출석 보상, 친구 초대, 초대 코드, 초대 보상, 연속 출석, 업적 이벤트 / campaign, event, attendance, check-in, referral, invite, streak, achievement milestone | `@skeleton/rewards` |
| `campaign-jdbc` | 이벤트 · 출석 · 초대 코드 · 초대 관계 저장을 DB 에 둔다 (PostgreSQL · MySQL). | `--modules campaign-jdbc` | 이벤트 저장, 출석 저장 / campaign store, attendance store | `@skeleton/rewards` |
| `captcha-turnstile` | 봇 방지 — Cloudflare Turnstile 토큰을 서버에서 검증한다(TurnstileVerifier). | 스타터(apps/api)에 기본 포함 | 캡차, 봇 방지, 스팸 방지, 로봇 확인, 가입 폼 보호 / captcha, turnstile, bot protection, spam protection, cloudflare turnstile | `@skeleton/captcha-turnstile` |
| `catalog` | 상품 · 가격 카탈로그 — 운영자가 화면에서 상품과 가격(월 · 연 · 단건 · 행사가 · 통화별)을 만들고, 가격표 · 구독 화면 · 결제가 방문 나라에 맞는 통화의 그 표를 읽는다. 결제 계약(SubscriptionPlans · PaymentCatalog · 결제사 가격 id)을 구현하고 가격은 고치지 않는다(새 줄 + 판매 중지). | `--modules catalog,catalog-jdbc` | 상품, 가격표, 요금제, 월간 연간, 행사가, 통화별 가격, 상품 관리 / product catalog, pricing table, price list, yearly monthly plans, sale price, multi-currency pricing | `@skeleton/payment` |
| `catalog-jdbc` | 상품 · 이름 · 가격 · 결제사 링크를 DB 에 둔다 (PostgreSQL · MySQL) — 같은 상품의 가격 쓰기는 상품 줄 잠금, 코드 · 등급 · 결제사 가격 id 는 유니크. | `--modules catalog-jdbc` | 상품 저장소, 가격 DB / catalog storage, price repository | `@skeleton/payment` |
| `config-aws-ssm` | AWS SSM Parameter Store 의 값을 시작할 때 스프링 프로퍼티로 불러온다. | `--modules config-aws-ssm` | AWS 설정, 파라미터 스토어, SSM, 비밀 불러오기 / aws ssm, parameter store, remote config, aws secrets | — |
| `coupon` | 쿠폰 — 단계 목록(무료 · 정률 · 정액, n회차)으로 마케팅이 행 하나로 만드는 정의, 쿠폰함, 공용 코드 등록, 보상 지급기 `coupon` 과 결제 할인 수단 `coupon`(예약 · 확정 · 해제 · 복원). | `--modules coupon,coupon-jdbc` | 쿠폰, 할인 코드, 프로모션 코드, 무료 이용권, n달 무료, 쿠폰함 / coupon, promo code, discount code, voucher, coupon wallet | `@skeleton/rewards` |
| `coupon-jdbc` | 쿠폰 정의 · 쿠폰함 · 공용 코드를 DB 에 둔다 (PostgreSQL · MySQL) — 예약 · 확정은 조건부 갱신, 발급 한도는 정의 줄 잠금. | `--modules coupon-jdbc` | 쿠폰 저장소, 쿠폰 DB / coupon storage, coupon repository | `@skeleton/rewards` |
| `crypto` | AES-GCM 텍스트 암호화 · 키 회전용 키 id 봉투 · URL 에 안전한 불투명 토큰 · 선택적 영속 변환기. | `--modules crypto` | 암호화, 복호화, 비밀 저장, 불투명 URL, 키 회전 / encryption, aes gcm, opaque token, key rotation, encrypt at rest | — |
| `dashboard` | 관리자 대시보드 조립기 — 다른 모듈이 빈으로 기여한 숫자 타일 · 순위 탭 · 같은 사람 묶음 퍼널을 모아 한 화면 분량(직전 기간 비교 · 그래프 시리즈 포함)으로 돌려주는 API(/api/v1/admin/dashboard). 계산은 프로젝트별 짧은 캐시를 지나 운영자가 여럿 열어도 DB · 외부 호출은 캐시 주기당 한 번이고, 카드 하나가 실패해도 그 카드만 상태로 말한다. 카드는 account-jdbc · auth-session-jdbc · payment-jdbc 나 앱이 기여한다. 권한 dashboard.read, 기본 꺼짐(skeleton.dashboard.admin.enabled). | `--modules dashboard` | 관리자 대시보드, 어드민 대시보드, 통계 화면, 매출 가입 숫자, 퍼널, 지표 카드 / admin dashboard, metrics, kpi tiles, funnel, analytics overview | `@skeleton/dashboard` |
| `dashboard-cloudflare` | 대시보드에 접속 숫자를 얹는다 — Cloudflare Web Analytics(쿠키 없음 · 정적 페이지 포함)의 방문 · 페이지뷰 타일과 유입 · 많이 본 페이지 · 국가 · 기기 순위. 어제까지는 새벽 적재 작업이 일별 표에 옮긴 값, 오늘은 5분 서버 캐시(운영자가 여럿이어도 외부 호출은 주기당 한 번). 키가 없으면 기동은 하고 그 카드만 「연결하면 보입니다」. 프로젝트 모드는 프로젝트마다 자기 계정 id · 사이트 태그 · 토큰. | `--modules dashboard-cloudflare` | 접속 통계, 방문자 수, 클라우드플레어 분석, 페이지뷰, 유입 경로, 국가별 방문 / cloudflare web analytics, visits, page views, referrers, traffic stats | `@skeleton/dashboard` |
| `dashboard-ga` | 대시보드에 흐름을 얹는다 — GA4 의 유입 채널 · 캠페인 · 첫 페이지 · 페이지 이동 · 새/재방문 순위(핵심 이벤트를 주면 전환 열까지)를 새벽 적재 일별 표에서 읽고(오늘은 GA 가 확정하기 전이라 비운다), 헤더의 현재 방문자 · 지금 보는 페이지 · 국가를 Realtime API 로 60초 서버 캐시를 거쳐 보여 준다(GET /api/v1/admin/dashboard/realtime). 서비스 계정 키는 라이브러리 없이 JWT 로 토큰을 받는다. 키가 없으면 기동은 하고 그 카드만 「연결하면 보입니다」. 프로젝트 모드는 프로젝트마다 자기 속성 id · 키. | `--modules dashboard-ga` | 구글 애널리틱스, GA4, 유입 채널, 캠페인 성과, 페이지 이동, 재방문율, 지금 접속자 / google analytics, ga4, channels, campaigns, landing pages, returning visitors, realtime visitors | `@skeleton/dashboard` |
| `dashboard-jdbc` | 대시보드 저장소 — PostgreSQL · MySQL 로 dashboard 의 저장 포트를 구현한다: 외부 출처의 일별 집계 표(dashboard_daily, 같은 날을 다시 적재해도 같은 행, 집계 숫자만)와 계정별 화면 설정 표(dashboard_layouts, 계정이 지워지면 같이 지운다). 붙이면 새벽 일별 적재 작업이 켜진다(적재기가 있을 때). | `--modules dashboard-jdbc` | 대시보드 저장소, 일별 집계 표, 화면 설정 저장 / dashboard storage, daily aggregates, dashboard layout storage | `@skeleton/dashboard` |
| `db-mysql` | MySQL 방언 — 드라이버 · Flyway MySQL 지원 · SqlDialect · Data JDBC 시간 변환, 세션을 UTC 로 고정한다. | `--db mysql` | MySQL, 마이SQL, DB 변경, MySQL 로 바꾸기 / mysql, mariadb, use mysql, switch database | — |
| `db-postgresql` | PostgreSQL 방언(기본) — 드라이버 · Flyway PostgreSQL 지원 · SqlDialect · Data JDBC 시간 변환. | 스타터(apps/api)에 기본 포함 | PostgreSQL, 포스트그레스, DB 방언, 데이터베이스 / postgresql, postgres, database dialect, database | — |
| `event-kafka` | 이벤트를 Kafka 로 발행하는 KafkaEventPublisher 와 키 · 메시지 · 직렬화 전략(켜기 전에는 로그만 남긴다). | `--modules event-kafka` | 카프카, 이벤트 발행, 메시지 큐, 이벤트 스트림 / kafka, event publishing, message broker, event stream | — |
| `idempotency` | Idempotency-Key 헤더로 명령 요청(결제 · 생성)의 중복 실행을 막고 첫 응답을 재생한다. | 스타터(apps/api)에 기본 포함 | 멱등, 중복 요청 방지, 두 번 눌러도 한 번, 멱등 키, 중복 결제 방지 / idempotency, idempotency key, duplicate request, replay response | — |
| `job-queue-jdbc` | DB 테이블 기반 재시도 작업 큐 — 백오프 · DEAD 처리 · FOR UPDATE SKIP LOCKED, Redis 없이 여러 인스턴스에서 안전. | 스타터(apps/api)에 기본 포함 | 작업 큐, 백그라운드 작업, 재시도, 비동기 작업, 내보내기 작업, 잡 큐 / job queue, background job, retry, dead letter, task queue | — |
| `json` | JsonCodec · 임의 JSON 을 담는 JsonDocument · 버전 있는 페이로드 · JPA/JDBC 변환기. | `--modules json` | JSON, JSON 칼럼, 버전 있는 페이로드, 직렬화 / json, json column, versioned payload, serialization | — |
| `legal` | 약관 · 개인정보 처리방침 · 동의 기록 — 종류 · 판(시행일 · DRAFT/REVIEWED) · 원문 해시를 못 박는 장부 · 가입 동의(코드 확인 때 계정과 같은 트랜잭션에 기록) · 선택 동의 철회 · 새 판 재동의(403) · 계정 삭제 때 익명화, HTTP /api/v1/legal 까지. 문서는 앱이 싣고 예시 TEMPLATE 은 prod 에서 막는다. | 스타터(apps/api)에 기본 포함 | 약관, 이용약관, 개인정보 처리방침, 동의, 재동의, 마케팅 수신 동의, 동의 기록, 약관 버전, 법적 문서, 동의 철회 / terms of service, privacy policy, consent, re-consent, marketing consent, consent record, document version, legal documents, withdraw consent | `@skeleton/legal` |
| `legal-jdbc` | 약관 · 동의 저장소 — PostgreSQL · MySQL 로 legal 의 저장 포트 둘(동의 사건 · 판 장부)을 구현한다(legal_consents · legal_document_versions, 트리거로 더하기만). | 스타터(apps/api)에 기본 포함 | 동의 저장소, 약관 DB / consent storage, consent repository, legal database | `@skeleton/legal` |
| `migration` | 마이그레이션 공통 규칙 — DB 를 지우는 설정이 허용 프로필 밖에서 켜지면 시작을 막는 가드(도구 중립). | 스타터(apps/api)에 기본 포함 | 마이그레이션 가드, DB 초기화 방지, 스키마 보호 / migration guard, prevent db wipe, schema safety | — |
| `migration-flyway` | Flyway 구현 — 로컬 clean 옵트인 · 마이그레이션 파일 이름 규칙(V<UTC 14자리>__snake_case.sql) 검사. | 스타터(apps/api)에 기본 포함 | Flyway, 마이그레이션, DB 스키마 버전, 스키마 변경 / flyway, database migration, schema migration, schema version | — |
| `notification` | 알림 계약 · 수신자 해석 · 인메모리 브로커 + 로그인한 사람의 받은편지함 HTTP(/api/v1/notifications: 목록 · 안 읽은 수 · 읽음 · 모두 읽음). 클릭 이동 링크 · 같은 열쇠 묶기 · 종류별 수신 끄기(/preferences, 끌 수 있는 종류는 설정) · 보관 정리(기본 꺼짐) · 커밋 뒤 전달. | `--modules notification` | 알림, 알림 목록, 받은편지함, 안 읽은 알림, 알림 읽음, 알림 보내기, 알림 묶기, 알림 링크, 알림 끄기, 알림 수신 설정, 알림 보관 정리 / notification, inbox, unread, mark as read, publish notification, collapse notifications, notification link, notification retention | `@skeleton/notifications` |
| `notification-jdbc` | 알림 받은편지함을 DB 테이블에 저장한다(notification_inbox). | `--modules notification-jdbc` | 알림 저장, 알림 DB, 알림 기록 유지 / notification storage, persist notifications, inbox database | `@skeleton/notifications` |
| `notification-mail` | SMTP 로 메일을 보내는 MailSender (spring.mail.* 위에). | 스타터(apps/api)에 기본 포함 | 메일 발송, 이메일 보내기, SMTP, 메일 / send email, smtp, mail sender, email | — |
| `notification-slack` | 알림과 @SlackException 예외 알림을 Slack 웹훅으로 보낸다. | `--modules notification-slack` | 슬랙 알림, 슬랙, 예외 알림 / slack notification, slack webhook, exception alert | — |
| `notification-sse` | 알림을 Server-Sent Events 로 브라우저에 실시간 전달한다(GET /api/v1/notifications/sse) — 받는 사람이 정해진 알림은 그 사람에게만. | `--modules notification-sse` | 실시간 알림, SSE, 알림 즉시 표시, 푸시 알림, 서버 푸시 / realtime notification, sse, server-sent events, push notification | `@skeleton/realtime` |
| `notification-websocket` | 알림을 STOMP over WebSocket(/ws/notifications)으로 전달한다 — 인증 · trace 인터셉터 · 하트비트를 갖춘 인프로세스 브로커. | `--modules notification-websocket` | 웹소켓, 실시간 알림, 양방향 통신, STOMP / websocket, stomp, realtime notification, two-way | `@skeleton/realtime` |
| `payment` | 결제 계약 + 장부 HTTP — PaymentService · PaymentProviderRouter(제공자는 payment-toss \| payment-kakaopay \| payment-naverpay \| payment-stripe \| payment-paddle). 장부(payment-jdbc)가 있으면 사용자 경로(/api/v1/payments: 설정 · 주문 만들기(PaymentCatalog 빈이 있을 때) · 승인 · 환불 요청), 꺼 둔 운영자 경로(/api/v1/admin/payments), 결제사가 부르는 웹훅 수신 경로, 탈퇴 고리 · 내보내기 고리를 연다. 구독(skeleton.payment.subscription.enabled — 꺼짐이 기본): 카드 등록 + 첫 결제, 기간 끝 갱신 작업, 해지 예약 (/api/v1/payments/subscriptions, 빌링키는 crypto 로 암호화). | `--modules payment` — `payment-toss` \| `payment-kakaopay` \| `payment-naverpay` \| `payment-stripe` \| `payment-paddle` 중 하나 이상 고른다 | 결제, 결제 승인, 환불, 결제 취소, 유료 / payment, checkout, refund, payment confirm, paid | `@skeleton/payment` |
| `payment-jdbc` | 결제 장부 저장소 — PostgreSQL · MySQL 로 payment 의 저장 포트(PaymentLedgerStore)와 작업 포트(PaymentTasks, job-queue-jdbc 에 싣는다)를 구현한다(payment_* 표 4개 · 주문 줄 락 · 유니크 · check 제약). | `--modules payment-jdbc` | 결제 장부, 주문 장부, 결제 저장소, 결제 DB / payment ledger, payment storage, payment repository, order ledger | `@skeleton/payment` |
| `payment-kakaopay` | 카카오페이 직접 연동 단건 결제 — 토스 계약 없이 카카오페이 계약만 있어도 쓰는 별도 결제사(기본 꺼짐). 결제 준비(ready) → 카카오톡 인증 → 서버 승인(approve) → 취소 · 환불 · 조회. 정기 결제(정기 가맹점 코드를 설정하면 — 1회차 결제가 곧 등록, 2회차부터 SID 로 갱신)는 있고 웹훅은 없다. | `--modules payment-kakaopay` | 카카오페이, 카카오페이 결제, 간편 결제, 토스 없이 결제 / kakaopay, kakao pay, korean easy pay, payment without toss | `@skeleton/payment` |
| `payment-naverpay` | 네이버페이 직접 연동 단건 결제 — 토스 계약 없이 네이버페이 계약만 있어도 쓰는 별도 결제사(기본 꺼짐). 서버 결제 예약(reserve) → 구매자가 예약한 결제창 → 서버 승인(apply) → 취소 · 환불 · 결제 내역 조회. 정기 결제(자동결제)는 정기 인증값(recurrent.*)을 설정하면 있고 웹훅은 없다. | `--modules payment-naverpay` | 네이버페이, 네이버페이 결제, 간편 결제, 토스 없이 결제 / naverpay, naver pay, korean easy pay, payment without toss | `@skeleton/payment` |
| `payment-paddle` | Paddle 해외 결제(판매 대행) — 서명된 웹훅으로 결제 · 구독 상태를 장부에 옮기고, 결제 · 갱신은 Paddle 이 한다. 구독 해지 · 플랜 올리기는 Paddle API. | `--modules payment-paddle` | 패들, 해외 결제, 해외 구독, 다통화, 플랜 변경 / paddle, merchant of record, international subscription, multi-currency, plan change | `@skeleton/payment` |
| `payment-stripe` | Stripe 결제 제공자 — 결제 승인 · 취소를 payment 의 제공자로 구현한다. | `--modules payment-stripe` | 스트라이프 결제, 해외 결제, 카드 결제 / stripe, stripe payments, card payment, international payment | `@skeleton/payment` |
| `payment-toss` | Toss 결제 제공자 — 결제 승인 · 취소를 payment 의 제공자로 구현한다. | `--modules payment-toss` | 토스 결제, 토스페이먼츠, 카드 결제, 간편 결제 / toss payments, toss, card payment | `@skeleton/payment` |
| `persistence-jdbc` | Spring Data JDBC 의 audit 타임스탬프 콜백과 DB 방언 전략(SqlDialect · SqlDialectVerifier), DB 에 못 닿는 기동 실패를 읽을 수 있는 메시지로 바꾸는 분석기와 첫 연결을 기다리는 선택 기능. | 스타터(apps/api)에 기본 포함 | JDBC, Data JDBC, audit 시각, 생성일 수정일, DB 접근 / jdbc, spring data jdbc, audit timestamps, created at updated at | — |
| `persistence-jooq` | jOOQ 연결 — audit 리스너 · MySQL UTC Instant 변환기, DDL 파일에서 코드 생성(빌드에 DB 불필요). | `--modules persistence-jooq` | jOOQ, 타입 안전 SQL, 쿼리 빌더 / jooq, type-safe sql, query builder, code generation | — |
| `persistence-jpa` | JPA 엔티티의 created_at · updated_at 자동 채움과 부분 수정 · fetch graph 도우미. | `--modules persistence-jpa` | JPA, 하이버네이트, 엔티티 audit, ORM / jpa, hibernate, entity audit, orm | — |
| `platform` | 모든 모듈의 공용 기반 — 표준 응답/에러 봉투 · 전역 예외 처리(필드 오류 FieldValidationException) · 모듈끼리 만나는 계약(AuthorDirectory · AccountErasureListener) · trace id · 요청 로깅 · CORS · rate limit · 외부 HTTP 클라이언트 · OpenAPI · 배포 가드 · 한 프로세스에 프로젝트 여럿(skeleton.projects: Host 로 프로젝트 판정 · 프로젝트별 스키마/풀/로그인 설정 · @ProjectApi — docs/multi-project.md). | 스타터(apps/api)에 기본 포함 | 에러 응답, 표준 응답, 예외 처리, 요청 로깅, 트레이스, CORS, 요청 제한, 외부 API 호출, 스웨거, 배포 가드, 프로젝트 여럿, 멀티 프로젝트, 도메인별 서비스 / error response, exception handling, request logging, trace id, cors, rate limit, http client, openapi, swagger, deploy guard, multi project, multi tenant host | `@skeleton/api-client` |
| `point` | 포인트 원장 — 적립(유효기간 선택) · 소멸이 가까운 묶음부터 차감 · 하루 한 번 소멸 · 운영자 조정. 보상 지급기(RewardIssuer, 종류 point)를 내 이벤트가 「포인트 n점」을 줄 수 있고, 같은 지급 건 · 같은 주문을 두 번 처리해도 한 번만 반영되며 잔액보다 큰 차감은 거절된다. 포인트를 쓰는 길은 서버 API(PointWallet.spend)뿐이고, 내 잔액 · 내역 읽기(/api/v1/points)와 운영 조정(/api/v1/admin/points, 권한 point.manage, 기본 꺼짐 skeleton.point.admin.enabled)이 있다. 저장소는 point-jdbc. | `--modules point` | 포인트, 적립금, 포인트 적립, 포인트 소멸, 포인트 차감, 포인트 조정 / points, loyalty points, point ledger, reward points, point expiry | `@skeleton/rewards` |
| `point-jdbc` | 포인트 저장소 — PostgreSQL · MySQL 로 point 의 저장 포트를 구현한다: 계정마다 잔액 한 줄(음수 불가)과 원장 표(적립 묶음의 남은 양 · 차감 · 소멸 · 조정, 같은 출처 키는 한 줄). 같은 계정의 쓰기는 잔액 줄을 잠가 직렬로 돌아 동시 차감이 같은 잔액을 두 번 쓰지 못한다. | `--modules point-jdbc` | 포인트 저장소, 포인트 원장 표 / point storage, point ledger tables | `@skeleton/rewards` |
| `redis-cache` | 이름 있는 Redis 캐시(TTL · 접두사) · 안정적인 키 생성기 · 캐시 오류 정책(기본 FAIL_OPEN). | `--modules redis-cache` | 캐시, Redis 캐시, 캐싱, 응답 캐시 / cache, redis cache, caching, ttl | — |
| `redis-core` | Redis 연결 · StringRedisTemplate · JSON 템플릿 · 키 접두사 — 다른 redis-* 모듈의 바탕. | `--modules redis-core` | Redis, 레디스, 키 접두사 / redis, redis connection, key prefix | — |
| `redis-lock` | Redisson 기반 분산 락 — @DistributedLock 어노테이션과 락 실행기. | `--modules redis-lock` | 분산 락, 락, 동시 실행 방지, 중복 실행 방지 / distributed lock, lock, mutex, redisson | — |
| `redis-rate-limit` | platform 의 rate limit 저장소를 Redis 고정 윈도 카운터로 바꾼다(여러 인스턴스에서 한도 공유). | `--modules redis-rate-limit` | 요청 제한, 속도 제한, API 호출 제한, 도배 방지 / rate limit, throttling, request limit, redis rate limit | — |
| `review` | 리뷰 · 평점 — 아무 대상(종류 + id)에 별점 1~5 · 본문, 계정×대상 하나(고쳐 쓴다) · 대상별 평균/분포(원자적 집계) · 정렬/거르기 · 도움돼요 · 구매 인증(앱이 ReviewPurchases.record 로 신호를 준다) · 구매자만 옵션 · 신고 · 운영 큐 · 숨김/되살리기/문제없음 · 자동 가림(옵션) · 운영자 답글 · 사진(저장 키 + 서명 주소 302) · 이벤트 고리(쓰기 트랜잭션 안, 보상은 앱이 잇는다) · 비로그인 읽기(앱이 켠다) · 탈퇴. 상품 · 결제 · 보상을 모른다. | `--modules review,review-jdbc` | 리뷰, 후기, 별점, 평점, 구매 인증, 도움돼요, 상품 후기 / review, rating, star rating, verified purchase, helpful, product reviews | `@skeleton/reviews` |
| `review-jdbc` | 리뷰 · 대상별 집계 · 도움돼요 · 구매 신호를 DB 에 둔다 (PostgreSQL · MySQL) — 집계는 원자적 UPDATE, 새 줄은 insertIgnore 먼저(틈 잠금 교착 없음), 목록 질의는 공개 줄만 읽는다. | `--modules review-jdbc` | 리뷰 저장소, 리뷰 DB / review storage, review repository | `@skeleton/reviews` |
| `reward` | 보상 지급의 허리 — 이벤트가 기록한 지급 건을 일꾼이 쿠폰 · 포인트 지급기로 주고(재시도 · 멱등), 받았어요 알림과 운영자 다시 지급 · 취소까지. | `--modules reward,reward-jdbc` | 보상 지급, 지급 건, 쿠폰 지급, 포인트 지급, 다시 지급, 받았어요 알림 / reward grant, reward issuer, payout worker, retry grant | `@skeleton/rewards` |
| `reward-jdbc` | 지급 건 저장과 지급 작업을 DB 에 두고 일꾼을 job-queue-jdbc 에 얹는다 (PostgreSQL · MySQL). | `--modules reward-jdbc` | 지급 건 저장, 지급 작업 큐 / reward grant store, reward job | `@skeleton/rewards` |
| `scheduler` | 어노테이션 기반 스케줄러 — 실행 가드 · 락 매니저 · 실패 핸들러(기본 락은 no-op, 단일 인스턴스용). | `--modules scheduler` | 스케줄러, 정기 작업, 크론, 주기 실행, 배치 / scheduler, cron, scheduled job, periodic task, batch | — |
| `search` | DB 전문 검색 — 다른 모듈이 밀어 넣은 문서(상품 · 노트 …)를 한국어 2글자 조각으로 찾는다(PostgreSQL tsvector · MySQL ngram, 확장 설치 없음). 질의(쪽 · 종류 · 범위 · 정렬 · 하이라이트 위치) · 주인 문서 가리기 · 재색인 작업 · 탈퇴. 밀어 넣기 포트는 platform 의 SearchIndexer 라 다른 모듈이 search 를 모른다. | `--modules search,search-jdbc` | 검색, 전문 검색, 한국어 검색, 부분 일치, 하이라이트, 재색인 / search, full-text search, korean search, ngram, highlight, reindex | `@skeleton/search` |
| `search-jdbc` | 검색 색인을 DB 에 둔다 (PostgreSQL tsvector + GIN · MySQL ngram FULLTEXT) — 쓰기는 호출자 트랜잭션에 합류, 재색인은 job-queue-jdbc 작업. MySQL 마이그레이션이 불용어를 꺼서 영어 조각이 빠지지 않게 한다. | `--modules search-jdbc` | 검색 저장소, 검색 색인 DB / search storage, search index database | — |
| `storage` | 저장소 계약(ObjectStorage · PresignedStorage) · 파일 크기/확장자/content-type 검증 + 브라우저 직접 업로드 HTTP(/api/v1/storage: presign · 멀티파트). | `--modules storage,storage-s3` | 파일 업로드, 이미지 업로드, 첨부파일, 프리사인, 파일 검증 / file upload, image upload, attachment, presigned url, multipart upload | `@skeleton/storage` |
| `storage-s3` | S3 호환 저장소(AWS S3 · Cloudflare R2 · MinIO) — presign PUT/GET · 멀티파트 · 복사 · 일괄 삭제, 공개 URL RAW \| OPAQUE. | `--modules storage-s3` | S3, R2, 클라우드 스토리지, 파일 저장소, 이미지 저장 / s3, r2, cloudflare r2, minio, object storage | `@skeleton/storage` |
| `time` | 글로벌 시간 — 요청마다 뷰어 시간대 · 로케일 · ZonedMoment(미래 현지 시각) · 사람이 읽는 이중 포맷 · 국가 → 시간대. | 스타터(apps/api)에 기본 포함 | 시간대, 타임존, 날짜 표시, 현지 시간, 예약 시각, 글로벌 / timezone, time zone, locale, zoned moment, date format, global time | `@skeleton/time` |
| `workspace` | 워크스페이스(팀 · 조직) — 만들기 · 소유자/관리자/멤버 역할 · 주소(slug, 바꿔도 옛 주소가 따라온다) · 멤버 관리 · 소유권 이전 · 메일 링크 초대(만료 · 재발송 · 취소 · 확인된 이메일로만 수락, 켜야 한다), 그리고 요청 헤더 `X-Workspace-Id` 로 앱 데이터를 묶는 경계 `CurrentWorkspace`. 좌석 상한은 앱이 정한다. 운영자 읽기 경로(목록 · 검색 · 상세, `workspace.read`)는 꺼져 있다가 앱이 켠다. | `--modules workspace,workspace-jdbc` | 워크스페이스, 팀, 조직, 멤버 역할, 소유권 이전, 멀티 테넌트 / workspace, team, organization, tenant, member roles, ownership transfer | `@skeleton/workspace` |
| `workspace-jdbc` | 워크스페이스 · slug 기록 · 멤버 · 초대를 DB 에 둔다 (PostgreSQL · MySQL) — 소유자는 유니크 표시로 늘 하나, 모든 변경은 워크스페이스 줄 잠금 아래에서 현재 역할로 판정. | `--modules workspace-jdbc` | 워크스페이스 저장소, 팀 DB / workspace storage, workspace repository | — |

### 앱 (`apps/*`)

| id | 요약 | 켜는 조각 | 키워드 (한국어 / 영어) | 짝 프런트 |
|---|---|---|---|---|
| `app-api` | 스타터 — 새 프로젝트가 복사해 시작하는 최소 조립(platform · auth · persistence-jdbc · db-postgresql · migration-flyway · time)과 HelloController 하나. Redis · Kafka · S3 · 메일 없이 부팅한다. | 스타터(apps/api)에 기본 포함 | 스타터, 시작 템플릿, 새 앱, 최소 구성, 기본 앱 / starter, boilerplate, minimal app, new app, template |  |
| `app-sample` | 참조 앱 Notes — 로그인한 사람이 첨부 있는 노트를 관리하는 제품 모양의 작은 앱(스타터 + idempotency · notification-jdbc/sse · storage-s3 · job-queue-jdbc · board · author-profile · alert-jdbc · search-jdbc 의 노트·상품 검색 · review-jdbc 의 상품 리뷰(산 사람만, 결제 → 구매 신호, 리뷰 → 보상) · payment-jdbc/payment-toss 의 결제 「응원하기」). 로그인 아이디를 켜고 이메일은 쓰기 때 받는다. 새 기능은 이 앱의 한 조각을 따라 한다. | `--with-sample` | 참조 앱, 예제 앱, 샘플, 노트 앱, 제품 모양 예시, 결제 예제, 응원하기 / sample app, reference app, example, notes app, payment example, cheer |  |
| `app-workbench` | 데모 — 모든 모듈을 함께 얹고 샘플 엔드포인트(/api/v1/skeleton · /api/v1/examples)와 통합 테스트로 모듈이 공존함을 증명한다(react-skeleton 워크벤치 화면의 백엔드). | `--with-workbench` | 워크벤치, 모듈 데모, 전체 모듈, 모든 모듈 함께 / workbench, demo, all modules, module smoke test |  |

### 스크립트 (`scripts/*`)

| id | 요약 | 켜는 조각 | 키워드 (한국어 / 영어) | 짝 프런트 |
|---|---|---|---|---|
| `script-build-capabilities` | capabilities.json 에서 docs/capabilities.md · llms.txt 를 생성하고(--check 로 어긋남 검사) 카탈로그를 정본 형식으로 정리한다. | (도구 — 켜는 조각 없음) | 기능 카탈로그, 카탈로그 생성, 카탈로그 점검, 기능 목록 / capabilities catalog, generate docs, catalog check, llms.txt | — |
| `script-check-real-providers` | 진짜 소셜 로그인 · 진짜 메일 · 진짜 토스 시험 전 점검 — .env.local 의 모양, 제공자 서버가 client id/secret 을 받아 주는지(가짜 코드로 한 번), 떠 있는 백엔드의 /auth/methods, SMTP 설정 · DNS(SPF/DMARC), 요청하면 시험 메일 한 통, 토스 테스트 키가 있으면 결제 장부가 기대는 응답(없는 결제의 조회 · 취소 · 멱등 키 · 응답 시간)을 돈이 움직이지 않는 호출 여섯 번(테스트 코드 헤더를 붙인 취소 포함)으로 ✓/✗ 줄로 말한다. | (도구 — 켜는 조각 없음) | 소셜 로그인 점검, 메일 발송 점검, 구글 카카오 키 확인, 시험 메일, 토스 키 점검 / check social login keys, smtp test mail, real provider readiness, toss test key check | — |
| `script-convert-ids-to-sortable` | 일회용 — 이미 있는 PostgreSQL DB 의 옛 행 id(접두사 + 32자 16진수 · UUID v4)를 시간순 id(접두사 + ULID · UUIDv7)로 바꾼다: pg_dump 백업 → 한 트랜잭션에서 id 를 가리키는 모든 문자열 칸을 같이 변환 → 행 수 · 옛 형식 0건 · 외래 키 검사(--dry-run 은 끝에서 되돌린다). | (도구 — 켜는 조각 없음) | id 변환, 시간순 id, ULID 변환, 기존 데이터 id 바꾸기 / convert ids, sortable ids, ULID migration, rewrite primary keys | — |
| `script-dev` | 로컬 풀스택 한 줄 실행 — DB(+ 로컬 S3) 컨테이너를 올리고 백엔드를 띄운다. 옆의 ../web 프런트가 있으면 같이 띄운다. | (도구 — 켜는 조각 없음) | 로컬 실행, 개발 서버, 한 줄 실행, DB 띄우기 / run locally, dev server, local stack, start database | — |
| `script-dev-multi` | 한 프로세스에 프로젝트 둘(a.localhost · b.localhost) 견본을 로컬에서 띄운다 — Docker Postgres 에 프로젝트 계정 · 스키마(scripts/project-account.sql, 멱등)를 만들고 multi 프로필로 apps/api 를 기동한다. | (도구 — 켜는 조각 없음) | 프로젝트 여럿 로컬 실행, 멀티 프로젝트 견본 / multi project local run, multi tenant sample | — |
| `script-dev-sample` | 샘플 앱 Notes 풀스택 한 줄 실행 — DB + 로컬 S3 → 백엔드(apps/sample) → 짝 프런트(react-skeleton 의 apps/sample). | (도구 — 켜는 조각 없음) | 샘플 실행, 노트 앱 실행 / run sample, notes app | — |
| `script-migrations-lock` | 동결된 Flyway 마이그레이션의 잠금(migrations.lock: 경로 + sha256 + 업그레이드 시험의 baseline)을 검사하고(--check) 새 파일만 더한다 — 기존 줄의 변경은 --rewrite <경로> 로만. | (도구 — 켜는 조각 없음) | 마이그레이션 잠금, 마이그레이션 동결, 체크섬 불일치, 마이그레이션 수정 금지 / migration lock, frozen migrations, flyway checksum, migrations.lock | — |
| `script-native-build` | GraalVM 25 공식 Docker 이미지 안에서 앱을 네이티브 실행 파일로 만든다(선택 빌드 -Pnative, 기본 JVM 빌드는 그대로) — 결과 apps/<앱>/build/native/nativeCompile/app. | (도구 — 켜는 조각 없음) | 네이티브 이미지, 네이티브 빌드, GraalVM, 콜드스타트 / native image, graalvm build, cold start | — |
| `script-native-smoke` | 떠 있는 tools/native-check 서버에 가입 · 로그인 · 갱신 · 가짜 결제사 결제 · 구독 · 작업 큐(깨우기 주소) · 웹훅을 실제 HTTP 로 돌려 본다. | (도구 — 켜는 조각 없음) | 네이티브 시험, 핵심 흐름 시험 / native smoke test, core flow check | — |
| `script-native-smoke-full` | 네이티브 --full 시험: 가짜 상대(메일 · 로컬 S3 · 소셜 5종 · 토스 · Paddle)를 붙여 메일 · 파일 저장 · 소셜 로그인 · 결제사 · SSE 흐름을 실제 HTTP 로 돌린다. | (도구 — 켜는 조각 없음) | 네이티브 전체 시험, 소셜 로그인 시험 / native full test, social login check | — |
| `script-native-test` | 앱을 네이티브로 빌드해 새 DB 와 함께 컨테이너에서 띄우고 흐름 시험(--smoke)을 돌린다 — 스켈레톤은 tools/native-check + native-smoke.sh, 새 프로젝트는 apps/api + 자기 시험(릴리스 전 · 의존성 파일 PR 에서 돌리는 것을 권한다). | (도구 — 켜는 조각 없음) | 네이티브 시험, 릴리스 전 시험 / native test, release check | — |
| `script-new-project` | 새 프로젝트 한 줄 찍기 — 이 레포를 복사해 고른 모듈 · 앱만 남기고, 이름 · 접두사를 바꾸고, 배포 선언 · 설정 블록 · 이 카탈로그를 걸러 다시 쓴다(--dry-run 은 고른 모듈과 따라온 이유만 보인다). | (도구 — 켜는 조각 없음) | 새 프로젝트, 프로젝트 만들기, 찍어내기, 스캐폴딩, 프로젝트 시작 / new project, scaffold, stamp, template, project generator | — |
| `script-payment-webhook` | 결제 웹훅을 손으로 넣어 본다 — 토스 PAYMENT_STATUS_CHANGED 모양의 본문을 떠 있는 서버의 수신함 주소로 보내 주소의 비밀 조각 · 재전송 중복 · 모르는 주문 · 본문 불신(서버가 결제사에 다시 조회)을 확인한다. 대상 서버는 늘 인자로 받는다. | (도구 — 켜는 조각 없음) | 웹훅 보내 보기, 결제 웹훅 시험, 수신함 시험, 토스 웹훅 / send test webhook, payment webhook check, toss webhook | — |
| `script-rename-skeleton` | 패키지 · 설정 접두사 · 클래스 이름 · 환경변수 접두사를 한 번에 바꾸는 스크립트(GitHub Template 로 만든 뒤 이름만 바꿀 때) — 끝에 남은 흔적을 검사하고 이 카탈로그를 다시 만든다. | (도구 — 켜는 조각 없음) | 이름 바꾸기, 리네임, 패키지 변경, 템플릿 이름 바꾸기 / rename, change package, rename template, project name | — |
| `script-sample-e2e-backend` | 샘플 앱 백엔드를 e2e 테스트용으로 올리고 내리는 비대화형 스크립트(react-skeleton 의 Playwright e2e 가 부른다). | (도구 — 켜는 조각 없음) | e2e 백엔드, e2e 테스트용 서버 / e2e backend, playwright backend | — |
| `script-test-deploy-contract` | 홈서버 배포 계약(docs/deploy.md)을 진짜 컨테이너로 증명한다 — 이미지 빌드 · 헬스체크 · 0.0.0.0 · stdout 로그 · 보호 환경 가드 실패 화면. | (도구 — 켜는 조각 없음) | 배포 검증, 배포 계약 테스트, 컨테이너 검증 / deploy contract test, container check, image verification | — |
| `script-test-new-project` | new-project.sh 의 테스트 — 빠른 검사(./gradlew check 가 부른다)와 --full(여러 조합과 레시피의 예제 명령을 정말 찍어 ./gradlew build). | (도구 — 켜는 조각 없음) | 찍기 테스트, 조합 검증, 스캐폴딩 테스트 / stamp test, combination test, scaffold test | — |

## 작업 예 (그대로 찍을 수 있는 두 레포의 명령)

### 커뮤니티 사이트 — 소셜 로그인 · 게시판(댓글 · 공감) · 실시간 알림 · 다국어 · 랜딩

> 관심사가 같은 사람들이 모이는 커뮤니티. 비로그인 방문자는 랜딩을 보고, 구글 · 카카오로 가입해 글을 쓰고 댓글 · 공감을 주고받으며, 내 글에 댓글이 달리면 알림이 바로 뜬다. 화면은 한국어 · 영어.

- kotlin(이 레포): `scripts/new-project.sh ~/work/community/api dev.example.community community Community --modules auth-social-google,board,board-jdbc,notification,notification-jdbc,notification-sse`
- react: `scripts/new-project.sh ~/work/community/web community --packages board,notifications,realtime,i18n,marketing,seo --with-sample`
- 짝 프런트 항목: `account-lifecycle`, `social-login`, `board`, `live-notifications`, `i18n`, `landing-page`, `seo`, `legal-documents`, `cookie-consent`

### 유료 SaaS 대시보드 — 로그인 · 대시보드/목록/설정 · 요금제 · 결제 · 알림

> 월 구독으로 쓰는 업무 도구. 로그인 후 대시보드 · 목록 · 상세 · 설정 화면이 있고, 요금제 페이지에서 플랜을 골라 토스로 결제하며, 가입 폼은 봇을 막고, 결제 · 작업 완료 알림을 받는다.

- kotlin(이 레포): `scripts/new-project.sh ~/work/saas/api dev.example.saas saas Saas --modules payment,payment-jdbc,payment-toss,notification,notification-jdbc,dashboard,dashboard-jdbc --db mysql`
- react: `scripts/new-project.sh ~/work/saas/web saas --packages marketing,payment,notifications,captcha-turnstile,dashboard --scope @acme`
- 짝 프런트 항목: `account-lifecycle`, `auth`, `screen-patterns`, `pricing-page`, `payment`, `notifications`, `captcha-turnstile`

### 콘텐츠 · 랜딩 사이트 (서버 렌더링) — 검색에 노출되는 소개 · 요금 · 약관 사이트

> 제품 소개 · 요금제 · 약관을 보여 주는 공개 사이트. 검색 결과와 링크 미리보기가 중요해 서버가 첫 HTML 을 그리고, 한국어 · 영어, 쿠키 동의 배너, 404 화면이 있다. 로그인 · 데이터 입력은 없다.

- kotlin(이 레포): `scripts/new-project.sh ~/work/site/api dev.example.site site Site`
- react: `scripts/new-project.sh ~/work/site/web site --packages marketing,i18n --ssr`
- 짝 프런트 항목: `app-starter-ssr`, `landing-page`, `pricing-page`, `legal-documents`, `cookie-consent`, `error-pages`, `i18n`

그다음의 설정 · 실행 · 검증 · 손으로 써야 하는 것: `docs/new-project-recipe.md` 의 같은 이름 예.

## 항목 상세

### `account` — 계정 수명주기 — 이메일 · 비밀번호 가입과 이메일 확인, 비밀번호 재설정 · 변경, 이메일 변경, 소셜 연결(자동 병합 없음), 탈퇴(다시 인증 → 유예 → 개인정보만 지우고 행은 ERASED 로 남김, 탈퇴 취소, 정지는 박제 + 해시 재가입 차단), 닉네임(중복 허용 · 유일 · 꼬리표 `닉네임#0417` 방식 · 필수 · 자동 닉네임 · 예약어, 작성자 이름 조회 AuthorDirectory 기본 구현), 이메일 필수(주소 없는 소셜 계정은 등록 전까지 모든 API 403 ACCOUNT.EMAIL_REQUIRED · 첫 등록은 재인증 없이, 기본 꺼짐 · `required-when=WRITE` 면 읽기는 통과하고 쓰기에서만 막는다), 로그인 아이디(이메일이 바뀌어도 안 바뀌는 비공개 간판 — 아이디 또는 이메일로 로그인 · 실시간 중복 확인 POST /account/login-id/availability · 아이디 정하기 게이트 POST /account/login-id · 아이디 찾기 메일 POST /account/login-id/forgot · 탈퇴해도 재사용 금지(해시), 기본 꺼짐), 연락받을 이메일(계정 이메일과 별개 · 복구 수단 아님 · 서비스 알림 수신 주소 SPI, 기본 꺼짐), 로그인 시도 제한, 관리자 도구, 첫 관리자, ko/en 메일 템플릿. auth 가 진짜 계정으로 로그인하게 한다.

- 종류 · 상태: module · experimental — 위치 `modules/account`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:account"))`
- 설정 접두사 `skeleton.account` — 키와 기본값 `docs/config/modules/account.yml`
- HTTP 경로: `/api/v1`, `/api/v1/account`, `/api/v1/account/contact-email`, `/api/v1/account/identities/social`, `/api/v1/admin/accounts`, `/api/v1/auth`
- 비밀 · 환경변수: `<P>_ACCOUNT_MAIL_LINK_BASE_URL` — 빠지면: 메일 링크가 여는 프론트 주소 — 비밀 아님(선언의 env:). 비면 보호 환경(<P>_ENV=stage|prod)에서 기동 실패
- 비밀 · 환경변수: `<P>_ACCOUNT_BOOTSTRAP_ADMIN_EMAIL` — 빠지면: 선택 — 이 이메일이 확인되면 ADMIN 을 준다. 없으면 관리자 API 를 켠 앱은 경고
- 비밀 · 환경변수: `<P>_ACCOUNT_BLOCKS_SECRET` — 빠지면: 재가입 차단 · 탈퇴한 로그인 아이디 해시(HMAC)의 키 — 선언의 secrets: (무작위로 충분). 로그인 아이디를 켠 앱은 보호 환경에서 비면 기동 실패, 관리자 API 만 켠 앱은 경고만(비면 JWT 비밀에서 파생돼 JWT 비밀을 돌리면 차단이 풀린다)
- 비밀 · 환경변수: `<P>_CLIENT_IP_MODE`, `<P>_CLIENT_IP_TRUSTED_PROXIES` (배포 플랫폼이 만들어 넣는다) — 빠지면: 홈서버 플랫폼이 넣는다(예약 이름). 안 정해지면 보호 환경에서 기동 실패 — IP 한도가 X-Forwarded-For 로 풀린다
- 문서: `docs/modules/account.md`
- 짝 프런트(react-skeleton): 항목 `auth`, `account-lifecycle`, `account-admin` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 메일 발송 자체는 notification-mail — 보호 환경에서는 메일 발송 길(notification-mail 또는 AccountMailTransport 빈)이 없으면 기동을 거부한다
  - 소셜 제공자 클라이언트는 auth-social-google | -kakao | -naver
  - 리프레시 토큰 · 세션 목록은 auth-session, 링크 로그인은 auth-magic-link
  - 소셜 로그인을 계정으로 이어 주는 것은 켜진 auth-social 과 skeleton.account.social.* 설정(모듈 기본은 가입 · 병합 모두 꺼짐)
- 키워드: 회원가입, 가입, 이메일 인증, 비밀번호 재설정, 비밀번호 찾기, 이메일 변경, 계정 삭제, 탈퇴, 계정 관리, 로그인 아이디, 아이디 찾기, 관리자 도구, 계정 정지, 프로필 / sign up, registration, email verification, password reset, forgot password, change email, delete account, account management, login id, username login, find my id, admin tools, suspend account, profile

### `account-jdbc` — account 의 저장소 · 토큰 · 감사 기록을 PostgreSQL · MySQL 로 — 스키마는 모듈 마이그레이션(MySQL 은 이메일 · 닉네임 키를 utf8mb4_bin 으로 정확 일치, 닉네임 방식 셋을 유니크 (키, 꼬리표) 하나로). 보호 환경에서 메모리 저장소를 막는 가드를 통과하는 방법.

- 종류 · 상태: module · experimental — 위치 `modules/account-jdbc`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:account-jdbc"))`
- 문서: `docs/modules/account-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `account-lifecycle` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - account 없이 단독으로는 의미가 없다 — account 의 포트 구현이다
  - 다른 DB 는 AccountRepository · OneTimeTokenStore 를 앱이 직접 구현한다
- 키워드: 계정 저장, 계정 DB, 회원 테이블, 계정 마이그레이션 / account storage, accounts table, account persistence, account migration

### `alert` — 주인 경보 — 5xx 몰림 · 기동 실패 · 죽은 작업을 Discord 호환 웹훅(+ 선택적 메일)으로 알린다. 에러 수집(Sentry)의 답.

- 종류 · 상태: module · stable — 위치 `modules/alert`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:alert"))`
- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): `job-queue-jdbc`, `notification-mail`
- 설정 접두사 `skeleton.alert` — 키와 기본값 `docs/config/modules/alert.yml`
- 비밀 · 환경변수: `<P>_ALERT_WEBHOOK_URL` — 빠지면: 경보가 로그로만 남는다 (기동은 된다)
- 문서: `docs/modules/alert.md`, `docs/alert.md`
- 쓰지 않는 경우:
  - Sentry 같은 에러 수집 · 스택 모음이 아니다 — 주인이 먼저 알아야 하는 일만 보낸다
  - 사용자에게 가는 알림은 notification
  - 웹훅 주소가 없으면 로그만 남긴다(기동은 된다)
- 키워드: 경보, 주인 알림, 에러 알림, 장애 알림, 디스코드 알림, 에러 수집, 5xx 알림 / owner alert, error reporting, discord webhook, incident alert, 5xx alert, sentry alternative

### `alert-jdbc` — 경보 기록을 DB 에 두어 여러 인스턴스 · 재시작을 가로질러 같은 경보를 접는다 (PostgreSQL · MySQL).

- 종류 · 상태: module · stable — 위치 `modules/alert-jdbc`
- 켜는 법: `--modules alert-jdbc`
- 의존 한 줄: `implementation(project(":modules:alert-jdbc"))`
- 설정 접두사 `skeleton.alert-jdbc` — 키와 기본값 `docs/config/modules/alert-jdbc.yml`
- 문서: `docs/modules/alert-jdbc.md`
- 쓰지 않는 경우:
  - 단일 인스턴스면 alert 의 메모리 저장소로 충분하다
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 경보 기록, 경보 중복 접기, 경보 DB / alert ledger, alert dedupe, alert database

### `api-key` — API 키(B2B 호출자용 장기 자격) — 사용자가 이름 · 범위 · 만료를 골라 발급(다시 인증)하고 본인이 회수하며, 키로 온 요청은 `@ApiKeyScope` 로 표시한 경로에 한해(기본 거부) 주인 계정으로 움직인다. 키별 한도 · 활성 키 상한 · 만료 상한은 앱이 정한다. 저장은 해시뿐.

- 종류 · 상태: module · experimental — 위치 `modules/api-key`
- 켜는 법: `--modules api-key,api-key-jdbc`
- 의존 한 줄: `implementation(project(":modules:api-key"))`
- 함께 골라야 한다: `api-key-jdbc`
- 설정 접두사 `skeleton.api-key` — 키와 기본값 `docs/config/modules/api-key.yml`
- HTTP 경로: `/api/v1/api-keys`, `/api/v1/admin/api-keys`
- 문서: `docs/modules/api-key.md`
- 쓰지 않는 경우:
  - api-key 는 저장소를 모른다 — api-key-jdbc 를 같이 고른다
  - 사람의 로그인(JWT)을 대신하지 않는다 — 키는 서버끼리의 호출용이고 운영 경로(역할)에는 닿지 않는다
  - 경로에 `@ApiKeyScope` 표시를 달지 않으면 키로 열리지 않는다 — 앱이 열 경로마다 표시 한 줄을 단다
  - OAuth 서버(클라이언트 자격 증명 흐름) · 서명 요청(HMAC)이 아니다
  - 운영자 경로(전체 목록 · 사유를 적는 회수)는 `skeleton.api-key.admin.enabled=true` 로 켠다 — 기본은 꺼짐(404)
- 키워드: API 키, 액세스 토큰, 개인 액세스 토큰, B2B API, 키 발급, 범위, 키 회수 / api key, personal access token, scoped key, api token, key revocation, per-key rate limit

### `api-key-jdbc` — API 키를 DB 에 둔다 (PostgreSQL · MySQL) — 토큰 해시만 저장하고, 회수 · 마지막 사용(1분 해상도)은 한 문장의 조건부 갱신이라 동시에 몰려도 한 번만 쓴다.

- 종류 · 상태: module · experimental — 위치 `modules/api-key-jdbc`
- 켜는 법: `--modules api-key-jdbc`
- 의존 한 줄: `implementation(project(":modules:api-key-jdbc"))`
- 자동으로 따라온다: `api-key`
- 문서: `docs/modules/api-key-jdbc.md`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: API 키 저장소, API 키 DB / api key storage, api key repository

### `async` — @Async · CompletableFuture 작업이 호출 스레드의 trace id · MDC · 보안 컨텍스트를 이어받는 실행기와 작업 그룹.

- 종류 · 상태: module · stable — 위치 `modules/async`
- 켜는 법: `--modules async`
- 의존 한 줄: `implementation(project(":modules:async"))`
- 설정 접두사 `skeleton.async` — 키와 기본값 `docs/config/modules/async.yml`
- 문서: `docs/modules/async.md`, `docs/async.md`
- 쓰지 않는 경우:
  - 재시도 · 영속이 필요한 작업은 job-queue-jdbc — 이 모듈은 프로세스 안 실행기다
- 키워드: 비동기, 백그라운드 작업, 스레드 풀, @Async, 작업 그룹 / async, background task, thread pool, mdc propagation, completable future

### `async-notification` — 비동기 작업에서 처리되지 않은 예외를 알림(notification)으로 흘려 보낸다.

- 종류 · 상태: module · stable — 위치 `modules/async-notification`
- 켜는 법: `--modules async-notification`
- 의존 한 줄: `implementation(project(":modules:async-notification"))`
- 자동으로 따라온다: `async`, `notification`
- 설정 접두사 `skeleton.async-notification` — 키와 기본값 `docs/config/modules/async-notification.yml`
- 문서: `docs/modules/async-notification.md`
- 쓰지 않는 경우:
  - 알림을 어디로 보낼지는 notification-* 모듈이 정한다
  - 주인에게 가는 경보는 alert
- 키워드: 비동기 예외 알림, 백그라운드 오류 알림, 비동기 에러 / async exception alert, background error notification, uncaught async exception

### `auth` — 로그인 — 비밀번호 로그인 · JWT 발급/검증 · 로컬 dev-login · 비상용 break-glass · SecurityFilterChain (계정 저장소는 앱이 정한다).

- 종류 · 상태: module · stable — 위치 `modules/auth`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:auth"))`
- 설정 접두사 `skeleton.auth` — 키와 기본값 `docs/config/modules/auth.yml`
- HTTP 경로: `/api/v1/auth`
- 비밀 · 환경변수: `JWT_SECRET`, `<P>_AUTH_JWT_SECRET` (배포 플랫폼이 만들어 넣는다) — 빠지면: 보호 환경(<P>_ENV=stage|prod · 프로필 prod|staging)에서 기동 실패: 비었음 / 내장 기본값 / 32바이트 미만
- 비밀 · 환경변수: `<P>_AUTH_BREAK_GLASS_SECRET`, `<P>_AUTH_BREAK_GLASS_ALLOWED_ACCOUNT_IDS` — 빠지면: break-glass 를 켰을 때만 — 비밀이 비면 기동 실패, 허용 계정이 비면 stage|prod 에서 기동 실패
- 문서: `docs/modules/auth.md`, `docs/deploy.md`
- 짝 프런트(react-skeleton): 항목 `auth` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 회원가입 · 비밀번호 재설정 · 계정 DB 저장 · 세션은 account · auth-session 이다 — 스타터에 이미 들어 있다(auth 만 쓰는 앱은 AuthAccountRepository 를 직접 구현한다 — 내장은 로컬 시드 계정뿐이고 prod · staging 에서는 기동을 거부한다)
  - 소셜 로그인은 auth-social
- 키워드: 로그인, 로그아웃, 인증, 토큰, JWT, 권한, 관리자 로그인, 비상 접근 / login, authentication, jwt, token, dev login, break glass, security

### `auth-magic-link` — 이메일로 받은 한 번 쓰는 링크로 로그인(비밀번호 없이) — 이미 있는 계정으로 들어오고(skeleton.auth-magic-link.sign-up 으로 가입도 허용), 미확인 계정에서는 가입 때의 미확인 비밀번호를 버린다.

- 종류 · 상태: module · experimental — 위치 `modules/auth-magic-link`
- 켜는 법: `--modules auth-magic-link`
- 의존 한 줄: `implementation(project(":modules:auth-magic-link"))`
- 설정 접두사 `skeleton.auth-magic-link` — 키와 기본값 `docs/config/modules/auth-magic-link.yml`
- HTTP 경로: `/api/v1/auth/magic-link`
- 문서: `docs/modules/auth-magic-link.md`
- 짝 프런트(react-skeleton): 항목 `magic-link-login` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 메일을 실제로 보내려면 notification-mail 이 필요하다
  - 비밀번호 로그인이 목적이면 필요 없다 — account 만으로 된다
  - 링크를 열기만 해서는 로그인되지 않는다 — 프런트가 redeem 을 POST 한다
- 키워드: 매직링크, 링크 로그인, 비밀번호 없는 로그인, 이메일 로그인 / magic link, passwordless, email sign in, link login

### `auth-session` — 로그인 세션 — 불투명한 리프레시 토큰(해시 저장 · 매번 회전 · 재사용 탐지 + 이벤트), 세션 목록 · 개별 로그아웃, body 전달(기본) 또는 HttpOnly 쿠키(CSRF 헤더). 끝난 세션 주기 청소와 계정 삭제 때 세션 행 삭제.

- 종류 · 상태: module · experimental — 위치 `modules/auth-session`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:auth-session"))`
- 설정 접두사 `skeleton.auth-session` — 키와 기본값 `docs/config/modules/auth-session.yml`
- HTTP 경로: `/api/v1/auth`
- 비밀 · 환경변수: `<P>_AUTH_SESSION_DELIVERY`, `<P>_AUTH_SESSION_COOKIE_SECURE` — 빠지면: 설정만 — 비밀이 아니다. 보호 환경에서 메모리 세션 저장소(auth-session-jdbc 를 얹는다)나 쿠키 전달에 cookie.secure=false 면 기동 실패
- 문서: `docs/modules/auth-session.md`
- 짝 프런트(react-skeleton): 항목 `auth`, `account-lifecycle`, `session-refresh` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 액세스 토큰(JWT) 발급은 auth — 이 모듈은 리프레시 · 세션이다
  - 계정 저장은 account · account-jdbc
  - 운영에서 메모리 세션 저장소는 기동 거부 — auth-session-jdbc 를 얹는다
- 키워드: 세션, 리프레시 토큰, 토큰 갱신, 로그인 유지, 기기 목록, 다른 기기 로그아웃 / session, refresh token, token rotation, keep signed in, device list, sign out everywhere

### `auth-session-jdbc` — auth-session 의 세션 · 리프레시 토큰 저장을 PostgreSQL · MySQL 로 — 한 토큰은 조건부 UPDATE 한 문장으로 한 번만 쓰이고, 인스턴스끼리 세션을 나눈다.

- 종류 · 상태: module · experimental — 위치 `modules/auth-session-jdbc`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:auth-session-jdbc"))`
- 문서: `docs/modules/auth-session-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `session-refresh` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - auth-session 없이 단독으로는 의미가 없다
  - 다른 DB 는 SessionStore 를 앱이 직접 구현한다
- 키워드: 세션 저장, 세션 DB, 리프레시 토큰 저장 / session storage, session table, refresh token storage

### `auth-social` — 소셜 로그인의 제공자 중립 계약 — POST /api/v1/auth/social/{provider}/login 라우팅 · 계정 연결 · 가입 정책 (제공자는 google · kakao · naver 모듈).

- 종류 · 상태: module · stable — 위치 `modules/auth-social`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:auth-social"))`
- 하나 이상 고른다: `auth-social-google` | `auth-social-kakao` | `auth-social-naver`
- 설정 접두사 `skeleton.auth-social` — 키와 기본값 `docs/config/modules/auth-social.yml`
- HTTP 경로: `/api/v1/auth/social`
- 문서: `docs/modules/auth-social.md`
- 짝 프런트(react-skeleton): 항목 `social-login` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 제공자 모듈(auth-social-google | -kakao | -naver) 없이는 로그인할 수 있는 제공자가 없다
  - 계정 연결 저장소는 메모리 기본(재시작하면 사라진다) · 가입 정책은 이미 연결된 계정만 통과 — 영속 저장과 자동 가입은 앱이 OAuthAccountLinkRepository · OAuthAccountProvisioningPolicy 로 구현한다
  - 로그인 버튼 · 콜백 화면은 프런트 몫
- 키워드: 소셜 로그인, 간편 로그인, 구글 로그인, 카카오 로그인, 네이버 로그인, OAuth, 계정 연결 / social login, oauth, sign in with google, kakao login, naver login, account linking

### `auth-social-google` — 구글 OAuth 제공자 — 토큰 교환 · 프로필 조회 클라이언트를 소셜 로그인의 제공자로 등록한다(클라이언트 id · secret 만 있으면 된다).

- 종류 · 상태: module · stable — 위치 `modules/auth-social-google`
- 켜는 법: `--modules auth-social-google`
- 의존 한 줄: `implementation(project(":modules:auth-social-google"))`
- 설정 접두사 없음 (별도 접두사 없음 — auth-social 의 providers.google 아래)
- 비밀 · 환경변수: `<P>_AUTH_SOCIAL_PROVIDERS_GOOGLE_ENABLED=true`, `<P>_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID`, `<P>_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_SECRET` — 빠지면: 켰는데 id · secret 이 비면 기동 실패 (client id/secret must not be blank)
- 문서: `docs/modules/auth-social-google.md`
- 짝 프런트(react-skeleton): 항목 `social-login` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - auth-social 없이는 쓰지 않는다(따라온다)
  - 로그인 버튼 · 콜백 화면은 프런트 몫
  - 설정은 auth-social 블록의 providers.google 아래에 둔다
- 키워드: 구글 로그인, 구글 소셜 로그인, 소셜 로그인 / google login, google oauth, social login

### `auth-social-kakao` — 카카오 OAuth 제공자 — 토큰 교환 · 프로필 조회 클라이언트를 소셜 로그인의 제공자로 등록한다(클라이언트 id · secret 만 있으면 된다).

- 종류 · 상태: module · stable — 위치 `modules/auth-social-kakao`
- 켜는 법: `--modules auth-social-kakao`
- 의존 한 줄: `implementation(project(":modules:auth-social-kakao"))`
- 설정 접두사 없음 (별도 접두사 없음 — auth-social 의 providers.kakao 아래)
- 비밀 · 환경변수: `<P>_AUTH_SOCIAL_PROVIDERS_KAKAO_ENABLED=true`, `<P>_AUTH_SOCIAL_PROVIDERS_KAKAO_CLIENT_ID`, `<P>_AUTH_SOCIAL_PROVIDERS_KAKAO_CLIENT_SECRET` — 빠지면: 켰는데 id · secret 이 비면 기동 실패 (client id/secret must not be blank)
- 문서: `docs/modules/auth-social-kakao.md`
- 짝 프런트(react-skeleton): 항목 `social-login` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - auth-social 없이는 쓰지 않는다(따라온다)
  - 로그인 버튼 · 콜백 화면은 프런트 몫
  - 설정은 auth-social 블록의 providers.kakao 아래에 둔다
- 키워드: 카카오 로그인, 카카오 소셜 로그인, 소셜 로그인 / kakao login, kakao oauth, social login

### `auth-social-naver` — 네이버 OAuth 제공자 — 토큰 교환 · 프로필 조회 클라이언트를 소셜 로그인의 제공자로 등록한다(클라이언트 id · secret 만 있으면 된다).

- 종류 · 상태: module · stable — 위치 `modules/auth-social-naver`
- 켜는 법: `--modules auth-social-naver`
- 의존 한 줄: `implementation(project(":modules:auth-social-naver"))`
- 설정 접두사 없음 (별도 접두사 없음 — auth-social 의 providers.naver 아래)
- 비밀 · 환경변수: `<P>_AUTH_SOCIAL_PROVIDERS_NAVER_ENABLED=true`, `<P>_AUTH_SOCIAL_PROVIDERS_NAVER_CLIENT_ID`, `<P>_AUTH_SOCIAL_PROVIDERS_NAVER_CLIENT_SECRET` — 빠지면: 켰는데 id · secret 이 비면 기동 실패 (client id/secret must not be blank)
- 문서: `docs/modules/auth-social-naver.md`
- 짝 프런트(react-skeleton): 항목 `social-login` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - auth-social 없이는 쓰지 않는다(따라온다)
  - 로그인 버튼 · 콜백 화면은 프런트 몫
  - 설정은 auth-social 블록의 providers.naver 아래에 둔다
- 키워드: 네이버 로그인, 네이버 소셜 로그인, 소셜 로그인 / naver login, naver oauth, social login

### `auth-social-oidc` — 범용 OpenID Connect 소셜 로그인 제공자 — 속성만으로 제공자를 여러 개 더한다(discovery · JWKS 키 교체 · ID 토큰 검증 · PKCE · nonce · LINE 프리셋). client-id 를 적은 제공자만 켜진다.

- 종류 · 상태: module · stable — 위치 `modules/auth-social-oidc`
- 켜는 법: `--modules auth-social-oidc`
- 의존 한 줄: `implementation(project(":modules:auth-social-oidc"))`
- 설정 접두사 `skeleton.auth-social-oidc` — 키와 기본값 `docs/config/modules/auth-social-oidc.yml`
- 비밀 · 환경변수: `<P>_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_ID`, `<P>_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_SECRET` — 빠지면: client id 가 비면 그 제공자는 없는 것(아무 일도 없다). id 만 있고 secret 이 비면 기동 실패 (client-secret must not be blank). 다른 제공자 코드는 <P>_AUTH_SOCIAL_OIDC_PROVIDERS_<코드>_… 로 같은 형태
- 문서: `docs/modules/auth-social-oidc.md`
- 짝 프런트(react-skeleton): 항목 `social-login` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - auth-social 없이는 쓰지 않는다(따라온다)
  - OIDC 가 아닌 순수 OAuth 2.0 제공자(X 는 auth-social-x, Kakao · Naver 는 각자 모듈)
  - 로그인 버튼 · 콜백 화면은 프런트 몫
- 키워드: 라인 로그인, LINE 로그인, OIDC, OpenID Connect, 글로벌 소셜 로그인, 마이크로소프트 로그인, 소셜 로그인 / line login, openid connect, oidc, microsoft login, social login, global social login

### `auth-social-x` — X(Twitter) 로그인 제공자 — OAuth 2.0 Authorization Code + PKCE(필수), 기밀 클라이언트(Basic), GET /2/users/me. client-id 를 적으면 켜진다.

- 종류 · 상태: module · stable — 위치 `modules/auth-social-x`
- 켜는 법: `--modules auth-social-x`
- 의존 한 줄: `implementation(project(":modules:auth-social-x"))`
- 설정 접두사 `skeleton.auth-social-x` — 키와 기본값 `docs/config/modules/auth-social-x.yml`
- 비밀 · 환경변수: `<P>_AUTH_SOCIAL_X_CLIENT_ID`, `<P>_AUTH_SOCIAL_X_CLIENT_SECRET` — 빠지면: client id 가 비면 제공자는 없는 것(아무 일도 없다). id 만 있고 secret 이 비면 기동 실패 (client-secret must not be blank)
- 문서: `docs/modules/auth-social-x.md`
- 짝 프런트(react-skeleton): 항목 `social-login` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - auth-social 없이는 쓰지 않는다(따라온다)
  - 공개 클라이언트(client secret 없는 SPA · 네이티브 앱 유형)의 X 앱 — 이 모듈은 기밀 클라이언트(Basic)만 쓴다
  - X API 의 티어 · 요금 조건은 X 정책 (확인 필요 — docs/modules/auth-social-x.md)
- 키워드: X 로그인, 엑스 로그인, 트위터 로그인, 글로벌 소셜 로그인, 소셜 로그인 / x login, twitter login, sign in with x, social login, global social login

### `author-profile` — 작성자 프로필 — 한 계정이 닉네임(프로필)을 여러 개 만들어 글 · 댓글마다 골라 쓰고, 두 프로필이 같은 사람이라는 사실이 다른 사용자에게 드러나지 않게 한다(공개 작성자 id prf_…, 독립 꼬리표, 즉시 삭제, 옛 글을 첫 프로필로 넘겨받기). 도메인 · 서비스 · 디렉터리 · 신원 확인 · 탈퇴 고리 · 내 프로필 HTTP(/api/v1/author-profiles: config · 목록 · 만들기 · 이름 바꾸기 · 지우기)까지이고 저장소는 author-profile-jdbc 가 맡는다.

- 종류 · 상태: module · experimental — 위치 `modules/author-profile`
- 켜는 법: `--modules author-profile,author-profile-jdbc`
- 의존 한 줄: `implementation(project(":modules:author-profile"))`
- 함께 골라야 한다: `author-profile-jdbc`
- 설정 접두사 `skeleton.author-profile` — 키와 기본값 `docs/config/modules/author-profile.yml`
- HTTP 경로: `/api/v1/admin/author-profiles`, `/api/v1/author-profiles`
- 문서: `docs/modules/author-profile.md`, `docs/author-profile-http-contract.md`
- 짝 프런트(react-skeleton): 항목 `author-profile` · 패키지 `@skeleton/author-profile` · 조각 `--packages author-profile`
- 쓰지 않는 경우:
  - 저장소를 모른다 — author-profile-jdbc 를 같이 고른다(없으면 시작에 실패한다). 다른 DB(JPA · jOOQ)로 저장하려면 포트 AuthorProfileRepository 를 앱이 구현한다
  - account 위에 얹힌 모듈이다 — account 없이 자기 AuthAccountRepository 만 쓰는 앱에는 맞지 않는다
  - 아바타 · 소개글 · 프로필 공개 페이지 · '이 프로필의 글 모아 보기'는 없다
  - storage(첨부)를 함께 쓰면 키에 계정 id 가 들어 두 프로필의 글이 이어진다 — skeleton.storage.web.key-owner: OPAQUE 와 비밀 <P>_STORAGE_WEB_KEY_SECRET(32자 이상, 선언의 secrets:)이 필요하다(docs/modules/author-profile.md)
- 키워드: 작성자 프로필, 닉네임 여러 개, 부캐, 익명 프로필, 프로필 선택, 글마다 닉네임 / author profile, multiple nicknames, pseudonym, alias, persona, unlinkable identities

### `author-profile-jdbc` — 작성자 프로필 저장소 — PostgreSQL · MySQL 로 author-profile 의 저장 포트를 구현한다(author_profiles 표 · (이름 키, 꼬리표)와 첫 프로필 유니크 · 첫 프로필 줄을 잠그는 원자적 상한).

- 종류 · 상태: module · experimental — 위치 `modules/author-profile-jdbc`
- 켜는 법: `--modules author-profile-jdbc`
- 의존 한 줄: `implementation(project(":modules:author-profile-jdbc"))`
- 자동으로 따라온다: `author-profile`
- 문서: `docs/modules/author-profile-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `author-profile` · 패키지 `@skeleton/author-profile` · 조각 `--packages author-profile`
- 쓰지 않는 경우:
  - author-profile 없이는 쓰지 않는다(따라온다)
  - 다른 DB(JPA · jOOQ)로 저장하려면 author-profile 의 저장 포트(AuthorProfileRepository)를 앱이 구현한다
- 키워드: 작성자 프로필 저장소, 프로필 DB / author profile storage, author profile repository, profile database

### `authz` — 운영자 역할 관리 — 역할 만들기 · 고치기 · 지우기(역할 → 권한 묶음)와 붙은 모듈이 선언한 권한 카탈로그 API(/api/v1/admin/roles · /api/v1/admin/permissions). 안전장치: 전권 역할 고정 · 자기에게 없는 권한은 못 줌(403) · 쓰는 계정이 있는 역할은 못 지움 · 자기 잠금 막기. 역할 변경은 OperatorAuditLog 계약으로만 알린다(기본 아무것도 안 함). 기본 꺼짐(skeleton.authz.admin.enabled).

- 종류 · 상태: module · experimental — 위치 `modules/authz`
- 켜는 법: `--modules authz,authz-jdbc`
- 의존 한 줄: `implementation(project(":modules:authz"))`
- 함께 골라야 한다: `authz-jdbc`
- 설정 접두사 `skeleton.authz.admin` — 키와 기본값 `docs/config/modules/authz.yml`
- HTTP 경로: `/api/v1/admin`
- 문서: `docs/modules/authz.md`, `docs/authz-http-contract.md`
- 짝 프런트(react-skeleton): 항목 `account-admin` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - authz 는 저장소를 모른다 — authz-jdbc 를 같이 고른다(켰는데 없으면 시작에 실패한다)
  - 역할 이름을 계정에 주는 일은 account 의 관리자 API(PUT /api/v1/admin/accounts/{id}/roles/{role}) — 이 모듈은 역할 → 권한 표만 다룬다
  - 운영 기록(누가 · 언제 · 무엇을) 표 · 조회 화면은 없다 — 역할 변경이 OperatorAuditLog.record 를 부를 뿐이다
  - 대상별 권한(이 게시판만) · 역할 상속 · 거절 규칙은 없다
- 키워드: 역할 관리, 권한 관리, 역할 만들기, 권한 묶음, 운영자 권한, CS 담당자 역할, 환불만 허용 / role management, permissions, role based access, admin roles, least privilege, custom role

### `authz-jdbc` — 역할 → 권한 표 저장소 — PostgreSQL · MySQL 로 authz 의 저장 포트를 구현하고(authz_roles · authz_role_permissions, 전권 역할은 표를 못 바꾼다) 판정이 읽는 역할 → 권한 매핑을 yml 에서 이 표로 바꾼다(캐시: 같은 인스턴스는 즉시, 다른 인스턴스는 skeleton.authz.cache-ttl). 첫 기동에 yml 매핑을 한 번만 옮긴다.

- 종류 · 상태: module · experimental — 위치 `modules/authz-jdbc`
- 켜는 법: `--modules authz-jdbc`
- 의존 한 줄: `implementation(project(":modules:authz-jdbc"))`
- 자동으로 따라온다: `authz`
- 문서: `docs/modules/authz-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `account-admin` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - authz 없이는 쓰지 않는다(따라온다)
  - 다른 DB(JPA · jOOQ)로 저장하려면 authz 의 저장 포트(AuthzRoleStore)와 RolePermissionSource 를 앱이 구현한다
- 키워드: 역할 권한 저장소, 권한 표 / role permission storage, authz database

### `badge` — 배지 — 앱이 정의하는 업적 배지의 정의 · 수여/회수(사유 필수) · 내 배지 · 프로필 배지(비로그인 읽기는 앱이 켠다) · 보상 지급기 `badge`. 포인트 · 쿠폰 · 이벤트를 모르고 조립으로만 같이 준다.

- 종류 · 상태: module · experimental — 위치 `modules/badge`
- 켜는 법: `--modules badge,badge-jdbc`
- 의존 한 줄: `implementation(project(":modules:badge"))`
- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): `notification`
- 함께 골라야 한다: `badge-jdbc`
- 설정 접두사 `skeleton.badge` — 키와 기본값 `docs/config/modules/badge.yml`
- HTTP 경로: `/api/v1/admin`, `/api/v1/badges`
- 문서: `docs/modules/badge.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - badge 는 저장소를 모른다 — badge-jdbc 를 같이 고른다
  - 자동 업적(조건을 세서 주기)은 이벤트 모듈의 몫이다 — 이 모듈은 앱이 직접 주거나 보상 지급기로 받기만 한다
  - 포인트 · 쿠폰을 같이 주는 일은 이 모듈이 하지 않는다 — 업적 단계의 보상 목록에 나란히 적는다
  - 필명(author-profile) 화면에 프로필 배지를 붙이지 않는다 — 주소의 계정 id 로 필명이 한 사람으로 이어진다
- 키워드: 배지, 업적, 뱃지, 프로필 배지, 수동 수여, 회수 / badge, achievement, profile badge, award badge, revoke badge

### `badge-jdbc` — 배지 정의 · 보유 · 수여/회수 내역을 DB 에 둔다 (PostgreSQL · MySQL) — 동시 수여는 보유 줄 유니크, 지급 건 재시도는 내역 유니크로 한 번.

- 종류 · 상태: module · experimental — 위치 `modules/badge-jdbc`
- 켜는 법: `--modules badge-jdbc`
- 의존 한 줄: `implementation(project(":modules:badge-jdbc"))`
- 자동으로 따라온다: `badge`, `notification`
- 설정 접두사 없음
- 문서: `docs/modules/badge-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 배지 저장소, 배지 DB / badge storage, badge repository

### `board` — 게시판 — 글 · 중첩 댓글(대댓글) · 설정으로 늘리는 반응(좋아요 · 공감 …) · 운영자 숨김/고정 · 댓글 알림 · 작성자 닉네임(authorName · authorTag, AuthorDirectory 로 요청당 한 번 조회), HTTP /api/v1/boards 까지.

- 종류 · 상태: module · stable — 위치 `modules/board`
- 켜는 법: `--modules board,board-jdbc`
- 의존 한 줄: `implementation(project(":modules:board"))`
- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): `notification`
- 함께 골라야 한다: `board-jdbc`
- 설정 접두사 `skeleton.board` — 키와 기본값 `docs/config/modules/board.yml`
- HTTP 경로: `/api/v1/boards`
- 문서: `docs/modules/board.md`
- 짝 프런트(react-skeleton): 항목 `board` · 패키지 `@skeleton/board` · 조각 `--packages board`
- 쓰지 않는 경우:
  - board 는 저장소를 모른다 — board-jdbc 를 같이 고른다(없으면 시작에 실패한다)
  - 채팅 · 피드 · 실시간 대화가 아니다 — 글 + 댓글 트리 모델
  - 게시판 코드(게시판 만들기)는 seed-boards 설정이나 운영자 API
- 키워드: 게시판, 커뮤니티, 글쓰기, 댓글, 대댓글, 공감, 좋아요, 반응, 운영자 숨김, 공지 고정 / board, forum, community, post, comment, reply, reaction, like, moderation

### `board-jdbc` — 게시판 저장소 — PostgreSQL · MySQL 로 board 의 저장 포트 네 개를 구현한다(boards · board_* 테이블 · 원자적 카운터).

- 종류 · 상태: module · stable — 위치 `modules/board-jdbc`
- 켜는 법: `--modules board-jdbc`
- 의존 한 줄: `implementation(project(":modules:board-jdbc"))`
- 자동으로 따라온다: `board`, `notification`
- 문서: `docs/modules/board-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `board` · 패키지 `@skeleton/board` · 조각 `--packages board`
- 쓰지 않는 경우:
  - board 없이는 쓰지 않는다(따라온다)
  - 다른 DB(JPA · jOOQ)로 저장하려면 board 의 저장 포트 네 개를 앱이 구현한다
- 키워드: 게시판 저장소, 게시판 DB / board storage, board repository, board database

### `campaign` — 이벤트 — 출석(매일 · 누적 · 연속 달성) · 친구초대(양쪽 보상) · 업적(앱이 보고한 수치가 단계에 닿으면)을 판정해 보상 지급 건으로 기록하고, 운영자가 이벤트를 만들고 켜고 끈다.

- 종류 · 상태: module · experimental — 위치 `modules/campaign`
- 켜는 법: `--modules campaign,campaign-jdbc,reward-jdbc`
- 의존 한 줄: `implementation(project(":modules:campaign"))`
- 자동으로 따라온다: `json`, `notification`, `reward`
- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): `scheduler`
- 함께 골라야 한다: `campaign-jdbc`, `reward-jdbc`
- 설정 접두사 `skeleton.campaign` — 키와 기본값 `docs/config/modules/campaign.yml`
- HTTP 경로: `/api/v1/admin/campaigns`, `/api/v1/campaigns`, `/api/v1/referrals`
- 문서: `docs/modules/campaign.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - campaign 은 저장소와 지급 기록을 모른다 — campaign-jdbc 와 reward-jdbc 를 같이 고른다(없으면 서비스가 켜지지 않는다)
  - 보상을 직접 주지 않는다 — 쿠폰 · 포인트 지급기는 별도 모듈이다(지급기가 없으면 지급 건이 FAILED 로 남는다)
  - 기념일 · 공유 · 달성 · 배지 이벤트는 다음 판
- 키워드: 이벤트, 출석 체크, 출석 보상, 친구 초대, 초대 코드, 초대 보상, 연속 출석, 업적 이벤트 / campaign, event, attendance, check-in, referral, invite, streak, achievement milestone

### `campaign-jdbc` — 이벤트 · 출석 · 초대 코드 · 초대 관계 저장을 DB 에 둔다 (PostgreSQL · MySQL).

- 종류 · 상태: module · experimental — 위치 `modules/campaign-jdbc`
- 켜는 법: `--modules campaign-jdbc`
- 의존 한 줄: `implementation(project(":modules:campaign-jdbc"))`
- 자동으로 따라온다: `campaign`, `json`, `notification`, `reward`, `scheduler`
- 설정 접두사 없음
- 문서: `docs/modules/campaign-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 이벤트 저장, 출석 저장 / campaign store, attendance store

### `captcha-turnstile` — 봇 방지 — Cloudflare Turnstile 토큰을 서버에서 검증한다(TurnstileVerifier).

- 종류 · 상태: module · stable — 위치 `modules/captcha-turnstile`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:captcha-turnstile"))`
- 설정 접두사 `skeleton.captcha-turnstile` — 키와 기본값 `docs/config/modules/captcha-turnstile.yml`
- 비밀 · 환경변수: `<P>_CAPTCHA_TURNSTILE_ENABLED=true`, `<P>_CAPTCHA_TURNSTILE_SECRET_KEY` — 빠지면: 검증기 빈이 없다 — 기동은 되고, 주입받는 곳이 있으면 그 빈 이름으로 실패
- 문서: `docs/modules/captcha-turnstile.md`, `docs/captcha-turnstile.md`
- 짝 프런트(react-skeleton): 항목 `captcha-turnstile` · 패키지 `@skeleton/captcha-turnstile` · 조각 `--packages captcha-turnstile`
- 쓰지 않는 경우:
  - 검증만 한다 — 화면의 위젯은 프런트(@skeleton/captcha-turnstile)
  - 켜기 전에는 검증기 빈이 없다
- 키워드: 캡차, 봇 방지, 스팸 방지, 로봇 확인, 가입 폼 보호 / captcha, turnstile, bot protection, spam protection, cloudflare turnstile

### `catalog` — 상품 · 가격 카탈로그 — 운영자가 화면에서 상품과 가격(월 · 연 · 단건 · 행사가 · 통화별)을 만들고, 가격표 · 구독 화면 · 결제가 방문 나라에 맞는 통화의 그 표를 읽는다. 결제 계약(SubscriptionPlans · PaymentCatalog · 결제사 가격 id)을 구현하고 가격은 고치지 않는다(새 줄 + 판매 중지).

- 종류 · 상태: module · experimental — 위치 `modules/catalog`
- 켜는 법: `--modules catalog,catalog-jdbc`
- 의존 한 줄: `implementation(project(":modules:catalog"))`
- 자동으로 따라온다: `crypto`, `payment`
- 함께 골라야 한다: `catalog-jdbc`
- 설정 접두사 `skeleton.catalog` — 키와 기본값 `docs/config/modules/catalog.yml`
- HTTP 경로: `/api/v1/admin/catalog`, `/api/v1/catalog`
- 문서: `docs/modules/catalog.md`
- 짝 프런트(react-skeleton): 항목 `payment`, `payment-admin` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - catalog 는 저장소를 모른다 — catalog-jdbc 를 같이 고른다
  - 결제 자체는 payment 와 결제사 모듈의 몫이다 — 이 모듈은 무엇을 얼마에 파는지의 표와 운영 화면용 API 만 낸다
  - 기존 구독자의 가격을 일괄 바꾸는 기능은 없다(가격은 고치지 않는다)
  - 무료 플랜은 표에 넣지 않는다
- 키워드: 상품, 가격표, 요금제, 월간 연간, 행사가, 통화별 가격, 상품 관리 / product catalog, pricing table, price list, yearly monthly plans, sale price, multi-currency pricing

### `catalog-jdbc` — 상품 · 이름 · 가격 · 결제사 링크를 DB 에 둔다 (PostgreSQL · MySQL) — 같은 상품의 가격 쓰기는 상품 줄 잠금, 코드 · 등급 · 결제사 가격 id 는 유니크.

- 종류 · 상태: module · experimental — 위치 `modules/catalog-jdbc`
- 켜는 법: `--modules catalog-jdbc`
- 의존 한 줄: `implementation(project(":modules:catalog-jdbc"))`
- 자동으로 따라온다: `catalog`, `crypto`, `payment`
- 설정 접두사 없음
- 문서: `docs/modules/catalog-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `payment`, `payment-admin` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 상품 저장소, 가격 DB / catalog storage, price repository

### `config-aws-ssm` — AWS SSM Parameter Store 의 값을 시작할 때 스프링 프로퍼티로 불러온다.

- 종류 · 상태: module · experimental — 위치 `modules/config-aws-ssm`
- 켜는 법: `--modules config-aws-ssm`
- 의존 한 줄: `implementation(project(":modules:config-aws-ssm"))`
- 설정 접두사 없음 — 키와 기본값 `docs/config/modules/config-aws-ssm.yml`
- 비밀 · 환경변수: `AWS_PROFILE`, `AWS 자격 증명(표준 AWS 환경변수)` — 빠지면: 고르지 않는다 — paths 를 정하면 부팅에서 자격 증명을 찾는다(fail-fast 설정에 따라 기동 실패)
- 문서: `docs/modules/config-aws-ssm.md`
- 쓰지 않는 경우:
  - 홈서버 배포에는 AWS 가 없다 — 고르지 않는다(deploy.md)
  - paths 를 정하면 부팅에서 AWS 자격 증명을 찾는다
- 키워드: AWS 설정, 파라미터 스토어, SSM, 비밀 불러오기 / aws ssm, parameter store, remote config, aws secrets

### `coupon` — 쿠폰 — 단계 목록(무료 · 정률 · 정액, n회차)으로 마케팅이 행 하나로 만드는 정의, 쿠폰함, 공용 코드 등록, 보상 지급기 `coupon` 과 결제 할인 수단 `coupon`(예약 · 확정 · 해제 · 복원).

- 종류 · 상태: module · experimental — 위치 `modules/coupon`
- 켜는 법: `--modules coupon,coupon-jdbc`
- 의존 한 줄: `implementation(project(":modules:coupon"))`
- 자동으로 따라온다: `json`
- 함께 골라야 한다: `coupon-jdbc`
- 설정 접두사 `skeleton.coupon` — 키와 기본값 `docs/config/modules/coupon.yml`
- HTTP 경로: `/api/v1/admin`, `/api/v1/coupons`
- 문서: `docs/modules/coupon.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - coupon 은 저장소를 모른다 — coupon-jdbc 를 같이 고른다
  - 쿠폰을 언제 주는지(출석 · 친구초대)는 이벤트 모듈의 몫이다 — 이 모듈은 지급기로 받기만 한다
  - 결제 금액을 깎는 일은 payment 의 몫이다 — 이 모듈은 할인 수단으로 쓸 수 있는지 판정하고 쥐었다 놓을 뿐이다
- 키워드: 쿠폰, 할인 코드, 프로모션 코드, 무료 이용권, n달 무료, 쿠폰함 / coupon, promo code, discount code, voucher, coupon wallet

### `coupon-jdbc` — 쿠폰 정의 · 쿠폰함 · 공용 코드를 DB 에 둔다 (PostgreSQL · MySQL) — 예약 · 확정은 조건부 갱신, 발급 한도는 정의 줄 잠금.

- 종류 · 상태: module · experimental — 위치 `modules/coupon-jdbc`
- 켜는 법: `--modules coupon-jdbc`
- 의존 한 줄: `implementation(project(":modules:coupon-jdbc"))`
- 자동으로 따라온다: `coupon`, `json`
- 설정 접두사 없음
- 문서: `docs/modules/coupon-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 쿠폰 저장소, 쿠폰 DB / coupon storage, coupon repository

### `crypto` — AES-GCM 텍스트 암호화 · 키 회전용 키 id 봉투 · URL 에 안전한 불투명 토큰 · 선택적 영속 변환기.

- 종류 · 상태: module · stable — 위치 `modules/crypto`
- 켜는 법: `--modules crypto`
- 의존 한 줄: `implementation(project(":modules:crypto"))`
- 설정 접두사 `skeleton.crypto` — 키와 기본값 `docs/config/modules/crypto.yml`
- 비밀 · 환경변수: `<P>_CRYPTO_PRIMARY_KEY_ID`, `<P>_CRYPTO_KEYS_<ID>(base64 AES 키)` — 빠지면: 키가 없으면 암호화 빈이 없다
- 문서: `docs/modules/crypto.md`, `docs/crypto.md`
- 쓰지 않는 경우:
  - 비밀번호 해시가 아니다(복호화할 수 있는 값용)
  - 키가 없으면 암호화 빈이 없다
- 키워드: 암호화, 복호화, 비밀 저장, 불투명 URL, 키 회전 / encryption, aes gcm, opaque token, key rotation, encrypt at rest

### `dashboard` — 관리자 대시보드 조립기 — 다른 모듈이 빈으로 기여한 숫자 타일 · 순위 탭 · 같은 사람 묶음 퍼널을 모아 한 화면 분량(직전 기간 비교 · 그래프 시리즈 포함)으로 돌려주는 API(/api/v1/admin/dashboard). 계산은 프로젝트별 짧은 캐시를 지나 운영자가 여럿 열어도 DB · 외부 호출은 캐시 주기당 한 번이고, 카드 하나가 실패해도 그 카드만 상태로 말한다. 카드는 account-jdbc · auth-session-jdbc · payment-jdbc 나 앱이 기여한다. 권한 dashboard.read, 기본 꺼짐(skeleton.dashboard.admin.enabled).

- 종류 · 상태: module · experimental — 위치 `modules/dashboard`
- 켜는 법: `--modules dashboard`
- 의존 한 줄: `implementation(project(":modules:dashboard"))`
- 설정 접두사 `skeleton.dashboard` — 키와 기본값 `docs/config/modules/dashboard.yml`
- HTTP 경로: `/api/v1/admin/dashboard`, `/api/v1/admin/dashboard/layout`
- 문서: `docs/modules/dashboard.md`, `docs/dashboard-http-contract.md`
- 짝 프런트(react-skeleton): 항목 `admin-dashboard` · 패키지 `@skeleton/dashboard` · 조각 `--packages dashboard`
- 쓰지 않는 경우:
  - 이 모듈에는 카드가 없다 — 가입 · 활성 · 매출 숫자는 account-jdbc · auth-session-jdbc · payment-jdbc 를 같이 고르면 기여되고, 앱 고유 숫자는 앱이 DashboardMetric · DashboardRanking 빈으로 더한다
  - 방문 · 유입 같은 접속 통계는 직접 세지 않는다 — Cloudflare · GA 출처 모듈이 기여한다
  - 원본 이벤트를 쌓는 분석 저장소가 아니다 — 집계 숫자만 다룬다
  - 여러 프로젝트를 합쳐 보는 총괄 화면은 없다(프로젝트마다 따로)
- 키워드: 관리자 대시보드, 어드민 대시보드, 통계 화면, 매출 가입 숫자, 퍼널, 지표 카드 / admin dashboard, metrics, kpi tiles, funnel, analytics overview

### `dashboard-cloudflare` — 대시보드에 접속 숫자를 얹는다 — Cloudflare Web Analytics(쿠키 없음 · 정적 페이지 포함)의 방문 · 페이지뷰 타일과 유입 · 많이 본 페이지 · 국가 · 기기 순위. 어제까지는 새벽 적재 작업이 일별 표에 옮긴 값, 오늘은 5분 서버 캐시(운영자가 여럿이어도 외부 호출은 주기당 한 번). 키가 없으면 기동은 하고 그 카드만 「연결하면 보입니다」. 프로젝트 모드는 프로젝트마다 자기 계정 id · 사이트 태그 · 토큰.

- 종류 · 상태: module · experimental — 위치 `modules/dashboard-cloudflare`
- 켜는 법: `--modules dashboard-cloudflare`
- 의존 한 줄: `implementation(project(":modules:dashboard-cloudflare"))`
- 자동으로 따라온다: `dashboard`
- 설정 접두사 `skeleton.dashboard-cloudflare` — 키와 기본값 `docs/config/modules/dashboard-cloudflare.yml`
- 비밀 · 환경변수: `<P>_DASHBOARD_CLOUDFLARE_API_TOKEN` — 빠지면: 토큰이 비거나 계정 id · 사이트 태그(설정, 비밀 아님)가 비면 Cloudflare 카드만 「연결하면 보입니다」 — 서버는 뜬다
- 문서: `docs/modules/dashboard-cloudflare.md`
- 짝 프런트(react-skeleton): 항목 `admin-dashboard` · 패키지 `@skeleton/dashboard` · 조각 `--packages dashboard`
- 쓰지 않는 경우:
  - 지난 기간(어제까지)을 보려면 dashboard-jdbc 도 같이 — 없으면 지난 날이 걸린 기간은 「기록 없음」이다
  - 프런트가 직접 세는 것(GA 의 페이지 이동 · 재방문 · 전환)은 dashboard-ga 몫이다
  - 원본 이벤트를 쌓지 않는다 — 집계 숫자만 일별 표에 옮긴다
  - 존 HTTP 분석(봇 · 이미지가 섞인 요청 수)은 읽지 않는다 — Web Analytics(RUM)만
- 키워드: 접속 통계, 방문자 수, 클라우드플레어 분석, 페이지뷰, 유입 경로, 국가별 방문 / cloudflare web analytics, visits, page views, referrers, traffic stats

### `dashboard-ga` — 대시보드에 흐름을 얹는다 — GA4 의 유입 채널 · 캠페인 · 첫 페이지 · 페이지 이동 · 새/재방문 순위(핵심 이벤트를 주면 전환 열까지)를 새벽 적재 일별 표에서 읽고(오늘은 GA 가 확정하기 전이라 비운다), 헤더의 현재 방문자 · 지금 보는 페이지 · 국가를 Realtime API 로 60초 서버 캐시를 거쳐 보여 준다(GET /api/v1/admin/dashboard/realtime). 서비스 계정 키는 라이브러리 없이 JWT 로 토큰을 받는다. 키가 없으면 기동은 하고 그 카드만 「연결하면 보입니다」. 프로젝트 모드는 프로젝트마다 자기 속성 id · 키.

- 종류 · 상태: module · experimental — 위치 `modules/dashboard-ga`
- 켜는 법: `--modules dashboard-ga`
- 의존 한 줄: `implementation(project(":modules:dashboard-ga"))`
- 자동으로 따라온다: `dashboard`
- 설정 접두사 `skeleton.dashboard-ga` — 키와 기본값 `docs/config/modules/dashboard-ga.yml`
- HTTP 경로: `/api/v1/admin/dashboard`
- 비밀 · 환경변수: `<P>_DASHBOARD_GA_CREDENTIALS` — 빠지면: 키가 비거나 속성 id(설정, 비밀 아님)가 비면 GA 카드만 「연결하면 보입니다」 — 서버는 뜬다
- 문서: `docs/modules/dashboard-ga.md`
- 짝 프런트(react-skeleton): 항목 `admin-dashboard` · 패키지 `@skeleton/dashboard` · 조각 `--packages dashboard`
- 쓰지 않는 경우:
  - 지난 기간(어제까지)을 보려면 dashboard-jdbc 도 같이 — 없으면 지난 날이 걸린 기간은 「기록 없음」이다
  - 전체 방문 수는 세지 않는다 — Cloudflare(dashboard-cloudflare)가 센다. GA 는 쿠키에 동의한 방문자만 본다
  - 오늘의 순위 숫자는 없다 — GA 일반 보고서가 몇 시간 늦게 확정되므로 어제까지만
  - 원본 이벤트를 쌓지 않는다 — 집계 숫자만 일별 표에 옮긴다
- 키워드: 구글 애널리틱스, GA4, 유입 채널, 캠페인 성과, 페이지 이동, 재방문율, 지금 접속자 / google analytics, ga4, channels, campaigns, landing pages, returning visitors, realtime visitors

### `dashboard-jdbc` — 대시보드 저장소 — PostgreSQL · MySQL 로 dashboard 의 저장 포트를 구현한다: 외부 출처의 일별 집계 표(dashboard_daily, 같은 날을 다시 적재해도 같은 행, 집계 숫자만)와 계정별 화면 설정 표(dashboard_layouts, 계정이 지워지면 같이 지운다). 붙이면 새벽 일별 적재 작업이 켜진다(적재기가 있을 때).

- 종류 · 상태: module · experimental — 위치 `modules/dashboard-jdbc`
- 켜는 법: `--modules dashboard-jdbc`
- 의존 한 줄: `implementation(project(":modules:dashboard-jdbc"))`
- 자동으로 따라온다: `dashboard`
- 문서: `docs/modules/dashboard-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `admin-dashboard` · 패키지 `@skeleton/dashboard` · 조각 `--packages dashboard`
- 쓰지 않는 경우:
  - dashboard 없이는 쓰지 않는다(따라온다)
  - dashboard 만으로도 우리 DB 숫자 카드는 돈다 — 이 모듈은 외부 출처(Cloudflare · GA)의 지난 기간 숫자와 기기 간 화면 설정이 필요할 때
  - 다른 DB(JPA · jOOQ)로 저장하려면 dashboard 의 저장 포트(DashboardDailyStore · DashboardLayoutStore)를 앱이 구현한다
- 키워드: 대시보드 저장소, 일별 집계 표, 화면 설정 저장 / dashboard storage, daily aggregates, dashboard layout storage

### `db-mysql` — MySQL 방언 — 드라이버 · Flyway MySQL 지원 · SqlDialect · Data JDBC 시간 변환, 세션을 UTC 로 고정한다.

- 종류 · 상태: module · stable — 위치 `modules/db-mysql`
- 켜는 법: `--db mysql`
- 의존 한 줄: `implementation(project(":modules:db-mysql"))`
- 문서: `docs/modules/db-mysql.md`
- 쓰지 않는 경우:
  - --modules 가 아니라 --db mysql 로 고른다(스타터의 db-postgresql 을 바꾼다)
  - --with-sample · --with-workbench 와 함께 쓸 수 없다(PostgreSQL 전용)
- 키워드: MySQL, 마이SQL, DB 변경, MySQL 로 바꾸기 / mysql, mariadb, use mysql, switch database

### `db-postgresql` — PostgreSQL 방언(기본) — 드라이버 · Flyway PostgreSQL 지원 · SqlDialect · Data JDBC 시간 변환.

- 종류 · 상태: module · stable — 위치 `modules/db-postgresql`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:db-postgresql"))`
- 문서: `docs/modules/db-postgresql.md`
- 쓰지 않는 경우:
  - db-* 중 정확히 하나만 — MySQL 이면 --db mysql
- 키워드: PostgreSQL, 포스트그레스, DB 방언, 데이터베이스 / postgresql, postgres, database dialect, database

### `event-kafka` — 이벤트를 Kafka 로 발행하는 KafkaEventPublisher 와 키 · 메시지 · 직렬화 전략(켜기 전에는 로그만 남긴다).

- 종류 · 상태: module · experimental — 위치 `modules/event-kafka`
- 켜는 법: `--modules event-kafka`
- 의존 한 줄: `implementation(project(":modules:event-kafka"))`
- 자동으로 따라온다: `json`
- 설정 접두사 `skeleton.event-kafka` — 키와 기본값 `docs/config/modules/event-kafka.yml`
- 문서: `docs/modules/event-kafka.md`
- 쓰지 않는 경우:
  - 소비(consumer)는 없다 — 발행만
  - 작은 프로젝트의 재시도 큐는 job-queue-jdbc 가 더 가볍다(브로커 불필요)
- 키워드: 카프카, 이벤트 발행, 메시지 큐, 이벤트 스트림 / kafka, event publishing, message broker, event stream

### `idempotency` — Idempotency-Key 헤더로 명령 요청(결제 · 생성)의 중복 실행을 막고 첫 응답을 재생한다.

- 종류 · 상태: module · stable — 위치 `modules/idempotency`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:idempotency"))`
- 설정 접두사 `skeleton.idempotency` — 키와 기본값 `docs/config/modules/idempotency.yml`
- 문서: `docs/modules/idempotency.md`
- 쓰지 않는 경우:
  - 기본 저장소는 인메모리(인스턴스별) — 여러 인스턴스면 앱이 IdempotencyStore 를 대체한다
- 키워드: 멱등, 중복 요청 방지, 두 번 눌러도 한 번, 멱등 키, 중복 결제 방지 / idempotency, idempotency key, duplicate request, replay response

### `job-queue-jdbc` — DB 테이블 기반 재시도 작업 큐 — 백오프 · DEAD 처리 · FOR UPDATE SKIP LOCKED, Redis 없이 여러 인스턴스에서 안전.

- 종류 · 상태: module · stable — 위치 `modules/job-queue-jdbc`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:job-queue-jdbc"))`
- 설정 접두사 `skeleton.job-queue` — 키와 기본값 `docs/config/modules/job-queue-jdbc.yml`
- HTTP 경로: `/internal/jobs/drain`
- 문서: `docs/modules/job-queue-jdbc.md`, `docs/job-queue-jdbc.md`
- 쓰지 않는 경우:
  - 핸들러는 멱등이어야 한다(재시도된다)
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
  - 메시지 브로커가 아니다(대량 스트림은 event-kafka)
- 키워드: 작업 큐, 백그라운드 작업, 재시도, 비동기 작업, 내보내기 작업, 잡 큐 / job queue, background job, retry, dead letter, task queue

### `json` — JsonCodec · 임의 JSON 을 담는 JsonDocument · 버전 있는 페이로드 · JPA/JDBC 변환기.

- 종류 · 상태: module · stable — 위치 `modules/json`
- 켜는 법: `--modules json`
- 의존 한 줄: `implementation(project(":modules:json"))`
- 문서: `docs/modules/json.md`, `docs/json.md`
- 쓰지 않는 경우:
  - 일반 DTO 직렬화는 스프링 기본(Jackson)으로 충분하다 — 저장되는 JSON 의 버전 관리용
- 키워드: JSON, JSON 칼럼, 버전 있는 페이로드, 직렬화 / json, json column, versioned payload, serialization

### `legal` — 약관 · 개인정보 처리방침 · 동의 기록 — 종류 · 판(시행일 · DRAFT/REVIEWED) · 원문 해시를 못 박는 장부 · 가입 동의(코드 확인 때 계정과 같은 트랜잭션에 기록) · 선택 동의 철회 · 새 판 재동의(403) · 계정 삭제 때 익명화, HTTP /api/v1/legal 까지. 문서는 앱이 싣고 예시 TEMPLATE 은 prod 에서 막는다.

- 종류 · 상태: module · stable — 위치 `modules/legal`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:legal"))`
- 함께 골라야 한다: `legal-jdbc`
- 설정 접두사 `skeleton.legal` — 키와 기본값 `docs/config/modules/legal.yml`
- HTTP 경로: `/api/v1/legal`, `/api/v1/legal/admin`, `/api/v1/legal/consents`
- 문서: `docs/modules/legal.md`, `docs/legal.md`, `docs/legal-http-contract.md`
- 짝 프런트(react-skeleton): 항목 `legal` · 패키지 `@skeleton/legal` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 법률 자문이 아니다 — 문서 본문 · 검토 · 사실(회사 이름 · 연락처)은 앱의 몫 (docs/legal.md)
  - legal 은 저장소를 모른다 — legal-jdbc 를 같이 고른다(없으면 시작에 실패한다)
  - 쿠키 배너 · 마케팅 수신 동의 화면은 프런트 — 이 모듈은 기록 · 검사만 한다
- 키워드: 약관, 이용약관, 개인정보 처리방침, 동의, 재동의, 마케팅 수신 동의, 동의 기록, 약관 버전, 법적 문서, 동의 철회 / terms of service, privacy policy, consent, re-consent, marketing consent, consent record, document version, legal documents, withdraw consent

### `legal-jdbc` — 약관 · 동의 저장소 — PostgreSQL · MySQL 로 legal 의 저장 포트 둘(동의 사건 · 판 장부)을 구현한다(legal_consents · legal_document_versions, 트리거로 더하기만).

- 종류 · 상태: module · stable — 위치 `modules/legal-jdbc`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:legal-jdbc"))`
- 자동으로 따라온다: `legal`
- 문서: `docs/modules/legal-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `legal` · 패키지 `@skeleton/legal` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - legal 없이는 쓰지 않는다(따라온다)
  - MySQL 은 트리거를 만드는 마이그레이션이 binlog 가 켜져 있으면 log_bin_trust_function_creators=1 이 필요하다
  - 다른 DB(JPA · jOOQ)로 저장하려면 legal 의 저장 포트 둘을 앱이 구현한다
- 키워드: 동의 저장소, 약관 DB / consent storage, consent repository, legal database

### `migration` — 마이그레이션 공통 규칙 — DB 를 지우는 설정이 허용 프로필 밖에서 켜지면 시작을 막는 가드(도구 중립).

- 종류 · 상태: module · stable — 위치 `modules/migration`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:migration"))`
- 설정 접두사 `skeleton.migration` — 키와 기본값 `docs/config/modules/migration.yml`
- 문서: `docs/modules/migration.md`, `docs/schema-management.md`
- 쓰지 않는 경우:
  - Flyway 구현은 migration-flyway(스타터가 이미 가진다)
  - 스키마를 직접 만들어 주지 않는다 — newMigration 태스크 · docs/schema-management.md
- 키워드: 마이그레이션 가드, DB 초기화 방지, 스키마 보호 / migration guard, prevent db wipe, schema safety

### `migration-flyway` — Flyway 구현 — 로컬 clean 옵트인 · 마이그레이션 파일 이름 규칙(V<UTC 14자리>__snake_case.sql) 검사.

- 종류 · 상태: module · stable — 위치 `modules/migration-flyway`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:migration-flyway"))`
- 문서: `docs/modules/migration-flyway.md`, `docs/schema-management.md`
- 쓰지 않는 경우:
  - Flyway 기본값을 바꾸지 않는다 — out-of-order 같은 선택은 앱 yml 의 명시적 override
- 키워드: Flyway, 마이그레이션, DB 스키마 버전, 스키마 변경 / flyway, database migration, schema migration, schema version

### `notification` — 알림 계약 · 수신자 해석 · 인메모리 브로커 + 로그인한 사람의 받은편지함 HTTP(/api/v1/notifications: 목록 · 안 읽은 수 · 읽음 · 모두 읽음). 클릭 이동 링크 · 같은 열쇠 묶기 · 종류별 수신 끄기(/preferences, 끌 수 있는 종류는 설정) · 보관 정리(기본 꺼짐) · 커밋 뒤 전달.

- 종류 · 상태: module · stable — 위치 `modules/notification`
- 켜는 법: `--modules notification`
- 의존 한 줄: `implementation(project(":modules:notification"))`
- 설정 접두사 `skeleton.notification.inbox`, `skeleton.notification.retention`, `skeleton.notification.preferences` — 키와 기본값 `docs/config/modules/notification.yml`
- HTTP 경로: `/api/v1/notifications`, `/api/v1/notifications/preferences`
- 문서: `docs/modules/notification.md`
- 짝 프런트(react-skeleton): 항목 `notifications` · 패키지 `@skeleton/notifications` · 조각 `--packages notifications`
- 쓰지 않는 경우:
  - 영속은 notification-jdbc, 실시간 전달은 notification-sse | -websocket, 메일은 notification-mail — 어댑터를 따로 고른다
  - 기본 브로커는 프로세스 안에서만 전달한다
  - 알림을 만드는 쪽(NotificationPublisher.publish)은 앱 코드가 부른다
- 키워드: 알림, 알림 목록, 받은편지함, 안 읽은 알림, 알림 읽음, 알림 보내기, 알림 묶기, 알림 링크, 알림 끄기, 알림 수신 설정, 알림 보관 정리 / notification, inbox, unread, mark as read, publish notification, collapse notifications, notification link, notification retention

### `notification-jdbc` — 알림 받은편지함을 DB 테이블에 저장한다(notification_inbox).

- 종류 · 상태: module · stable — 위치 `modules/notification-jdbc`
- 켜는 법: `--modules notification-jdbc`
- 의존 한 줄: `implementation(project(":modules:notification-jdbc"))`
- 자동으로 따라온다: `json`, `notification`
- 문서: `docs/modules/notification-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `notifications` · 패키지 `@skeleton/notifications` · 조각 `--packages notifications`
- 쓰지 않는 경우:
  - notification 없이는 쓰지 않는다(따라온다)
  - DataSource · db-* 모듈과 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 알림 저장, 알림 DB, 알림 기록 유지 / notification storage, persist notifications, inbox database

### `notification-mail` — SMTP 로 메일을 보내는 MailSender (spring.mail.* 위에).

- 종류 · 상태: module · stable — 위치 `modules/notification-mail`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:notification-mail"))`
- 설정 접두사 `skeleton.notification-mail` — 키와 기본값 `docs/config/modules/notification-mail.yml`
- 비밀 · 환경변수: `<P>_NOTIFICATION_MAIL_ENABLED=true`, `<P>_NOTIFICATION_MAIL_FROM`, `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` — 빠지면: host · from 이 비면 발송기 빈이 없다 · 비밀번호가 틀리면 첫 발송에서 실패
- 문서: `docs/modules/notification-mail.md`, `docs/notification-mail.md`
- 쓰지 않는 경우:
  - 수신 · 템플릿 엔진 · 메일 서비스(SES 등) 연동은 없다 — SMTP 만
  - host · from 을 정하기 전에는 꺼져 있다
- 키워드: 메일 발송, 이메일 보내기, SMTP, 메일 / send email, smtp, mail sender, email

### `notification-slack` — 알림과 @SlackException 예외 알림을 Slack 웹훅으로 보낸다.

- 종류 · 상태: module · stable — 위치 `modules/notification-slack`
- 켜는 법: `--modules notification-slack`
- 의존 한 줄: `implementation(project(":modules:notification-slack"))`
- 자동으로 따라온다: `notification`
- 설정 접두사 `skeleton.notification.slack` — 키와 기본값 `docs/config/modules/notification-slack.yml`
- 비밀 · 환경변수: `<P>_NOTIFICATION_SLACK_ENABLED=true`, `<P>_NOTIFICATION_SLACK_WEBHOOK_URL` — 빠지면: 아무것도 보내지 않는다
- 문서: `docs/modules/notification-slack.md`
- 쓰지 않는 경우:
  - Discord 로 가는 주인 경보는 alert
  - 웹훅 URL 이 없으면 아무것도 보내지 않는다
- 키워드: 슬랙 알림, 슬랙, 예외 알림 / slack notification, slack webhook, exception alert

### `notification-sse` — 알림을 Server-Sent Events 로 브라우저에 실시간 전달한다(GET /api/v1/notifications/sse) — 받는 사람이 정해진 알림은 그 사람에게만.

- 종류 · 상태: module · stable — 위치 `modules/notification-sse`
- 켜는 법: `--modules notification-sse`
- 의존 한 줄: `implementation(project(":modules:notification-sse"))`
- 자동으로 따라온다: `notification`
- 설정 접두사 `skeleton.notification.sse` — 키와 기본값 `docs/config/modules/notification-sse.yml`
- HTTP 경로: `/api/v1/notifications/sse`
- 문서: `docs/modules/notification-sse.md`
- 짝 프런트(react-skeleton): 항목 `realtime`, `live-notifications` · 패키지 `@skeleton/realtime` · 조각 `--packages realtime`
- 쓰지 않는 경우:
  - notification 없이는 쓰지 않는다(따라온다)
  - 서블릿 웹 앱용 — WebFlux 앱이 아니다
  - 양방향 통신은 notification-websocket
  - 인스턴스가 여러 개면 인프로세스 브로커라 다른 인스턴스의 알림을 못 받는다
- 키워드: 실시간 알림, SSE, 알림 즉시 표시, 푸시 알림, 서버 푸시 / realtime notification, sse, server-sent events, push notification

### `notification-websocket` — 알림을 STOMP over WebSocket(/ws/notifications)으로 전달한다 — 인증 · trace 인터셉터 · 하트비트를 갖춘 인프로세스 브로커.

- 종류 · 상태: module · stable — 위치 `modules/notification-websocket`
- 켜는 법: `--modules notification-websocket`
- 의존 한 줄: `implementation(project(":modules:notification-websocket"))`
- 자동으로 따라온다: `notification`
- 설정 접두사 `skeleton.notification-websocket` — 키와 기본값 `docs/config/modules/notification-websocket.yml`
- 문서: `docs/modules/notification-websocket.md`, `docs/notification-websocket.md`
- 짝 프런트(react-skeleton): 항목 `realtime`, `live-notifications` · 패키지 `@skeleton/realtime` · 조각 `--packages realtime`
- 쓰지 않는 경우:
  - notification 없이는 쓰지 않는다(따라온다)
  - authentication.enabled=true 가 되기 전까지 엔드포인트는 열려 있다
  - 단방향 알림이면 notification-sse 가 더 단순하다
  - 인스턴스가 여러 개면 인프로세스 브로커
- 키워드: 웹소켓, 실시간 알림, 양방향 통신, STOMP / websocket, stomp, realtime notification, two-way

### `payment` — 결제 계약 + 장부 HTTP — PaymentService · PaymentProviderRouter(제공자는 payment-toss | payment-kakaopay | payment-naverpay | payment-stripe | payment-paddle). 장부(payment-jdbc)가 있으면 사용자 경로(/api/v1/payments: 설정 · 주문 만들기(PaymentCatalog 빈이 있을 때) · 승인 · 환불 요청), 꺼 둔 운영자 경로(/api/v1/admin/payments), 결제사가 부르는 웹훅 수신 경로, 탈퇴 고리 · 내보내기 고리를 연다. 구독(skeleton.payment.subscription.enabled — 꺼짐이 기본): 카드 등록 + 첫 결제, 기간 끝 갱신 작업, 해지 예약 (/api/v1/payments/subscriptions, 빌링키는 crypto 로 암호화).

- 종류 · 상태: module · experimental — 위치 `modules/payment`
- 켜는 법: `--modules payment` — `payment-toss` | `payment-kakaopay` | `payment-naverpay` | `payment-stripe` | `payment-paddle` 중 하나 이상 고른다
- 의존 한 줄: `implementation(project(":modules:payment"))`
- 자동으로 따라온다: `crypto`
- 하나 이상 고른다: `payment-toss` | `payment-kakaopay` | `payment-naverpay` | `payment-stripe` | `payment-paddle`
- 설정 접두사 `skeleton.payment` — 키와 기본값 `docs/config/modules/payment.yml`
- HTTP 경로: `/api/v1/payments`, `/api/v1/admin/payments`, `/api/v1/payments/webhooks`
- 비밀 · 환경변수: `<P>_PAYMENT_WEBHOOK_PATH_TOKEN` — 빠지면: 서명 없는 결제사(토스의 결제 이벤트)의 웹훅 경로가 늘 404 이다 — 보호 환경(stage · prod)에서는 비었거나 약하면(24자 미만) 기동 실패
- 문서: `docs/modules/payment.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - 금액은 앱의 PaymentCatalog 빈이 정한다 — 브라우저가 금액을 정하는 길은 없다(카탈로그 빈이 없으면 주문 만들기 경로가 열리지 않는다)
  - 운영자 경로(skeleton.payment.admin.enabled)는 기본 꺼짐이고, 켜면 PaymentOperatorPolicy(기본: OperatorAccess 의 권한 payment.read · payment.refund · payment.operate)를 매 호출 확인한다
  - 본인은 환불을 요청만 한다 — 돈을 움직이는 환불 · 승인은 운영자 경로뿐이다
  - 구독 · 정기 결제 관리는 없다
  - 제공자 모듈 없이는 결제할 수 없다
  - 재시작에 남는 주문 장부(주문 · 환불 · 이력 · 수신함 · 뒷일 작업)는 payment-jdbc 를 같이 골라야 켜진다 — 없으면 결제 승인 파사드뿐이고 HTTP 도 열리지 않는다
- 키워드: 결제, 결제 승인, 환불, 결제 취소, 유료 / payment, checkout, refund, payment confirm, paid

### `payment-jdbc` — 결제 장부 저장소 — PostgreSQL · MySQL 로 payment 의 저장 포트(PaymentLedgerStore)와 작업 포트(PaymentTasks, job-queue-jdbc 에 싣는다)를 구현한다(payment_* 표 4개 · 주문 줄 락 · 유니크 · check 제약).

- 종류 · 상태: module · experimental — 위치 `modules/payment-jdbc`
- 켜는 법: `--modules payment-jdbc`
- 의존 한 줄: `implementation(project(":modules:payment-jdbc"))`
- 자동으로 따라온다: `crypto`, `payment`
- 문서: `docs/modules/payment-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - payment 없이는 쓰지 않는다(따라온다)
  - 결제사 모듈(payment-toss | payment-kakaopay | payment-naverpay | payment-stripe | payment-paddle)이 따로 필요하다 — 이 모듈은 저장소다
  - job-queue-jdbc 가 켜져 있어야 한다(꺼 두면 시작에 실패한다)
  - 다른 DB(JPA · jOOQ)로 저장하려면 payment 의 저장 포트(PaymentLedgerStore · PaymentTasks)를 앱이 구현한다
- 키워드: 결제 장부, 주문 장부, 결제 저장소, 결제 DB / payment ledger, payment storage, payment repository, order ledger

### `payment-kakaopay` — 카카오페이 직접 연동 단건 결제 — 토스 계약 없이 카카오페이 계약만 있어도 쓰는 별도 결제사(기본 꺼짐). 결제 준비(ready) → 카카오톡 인증 → 서버 승인(approve) → 취소 · 환불 · 조회. 정기 결제(정기 가맹점 코드를 설정하면 — 1회차 결제가 곧 등록, 2회차부터 SID 로 갱신)는 있고 웹훅은 없다.

- 종류 · 상태: module · experimental — 위치 `modules/payment-kakaopay`
- 켜는 법: `--modules payment-kakaopay`
- 의존 한 줄: `implementation(project(":modules:payment-kakaopay"))`
- 자동으로 따라온다: `crypto`, `payment`
- 설정 접두사 `skeleton.payment-kakaopay` — 키와 기본값 `docs/config/modules/payment-kakaopay.yml`
- 비밀 · 환경변수: `<P>_PAYMENT_KAKAOPAY_ENABLED=true`, `<P>_PAYMENT_KAKAOPAY_SECRET_KEY`, `<P>_PAYMENT_KAKAOPAY_CID_SECRET` — 빠지면: 제공자 빈이 없다 — 결제 라우팅이 그 제공자를 못 찾는다. 시크릿 키나 가맹점 코드(CID)가 비면 보호 환경(stage · prod)에서는 기동 실패, 그 밖의 환경은 경고
- 문서: `docs/modules/payment-kakaopay.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - payment 없이는 쓰지 않는다(따라온다)
  - 정기 결제(SID) · 웹훅은 아직 없다 — 결과는 승인 응답과 주문 조회로 맞춘다
  - 토스 계약이 있고 카카오페이를 간편결제 단추로만 받으면 payment-toss 의 easy-pays 로 충분하다
- 키워드: 카카오페이, 카카오페이 결제, 간편 결제, 토스 없이 결제 / kakaopay, kakao pay, korean easy pay, payment without toss

### `payment-naverpay` — 네이버페이 직접 연동 단건 결제 — 토스 계약 없이 네이버페이 계약만 있어도 쓰는 별도 결제사(기본 꺼짐). 서버 결제 예약(reserve) → 구매자가 예약한 결제창 → 서버 승인(apply) → 취소 · 환불 · 결제 내역 조회. 정기 결제(자동결제)는 정기 인증값(recurrent.*)을 설정하면 있고 웹훅은 없다.

- 종류 · 상태: module · experimental — 위치 `modules/payment-naverpay`
- 켜는 법: `--modules payment-naverpay`
- 의존 한 줄: `implementation(project(":modules:payment-naverpay"))`
- 자동으로 따라온다: `crypto`, `payment`
- 설정 접두사 `skeleton.payment-naverpay` — 키와 기본값 `docs/config/modules/payment-naverpay.yml`
- 비밀 · 환경변수: `<P>_PAYMENT_NAVERPAY_ENABLED=true`, `<P>_PAYMENT_NAVERPAY_CLIENT_SECRET` — 빠지면: 제공자 빈이 없다 — 결제 라우팅이 그 제공자를 못 찾는다. 클라이언트 시크릿 · 아이디 · 체인 아이디가 비면 보호 환경(stage · prod)에서는 기동 실패, 그 밖의 환경은 경고
- 문서: `docs/modules/payment-naverpay.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - payment 없이는 쓰지 않는다(따라온다)
  - 웹훅은 아직 없다 — 결과는 승인 응답과 결제 내역 조회로 맞춘다
  - 토스 계약이 있고 네이버페이를 간편결제 단추로만 받으면 payment-toss 의 easy-pays 로 충분하다
  - 네이버페이 인증값은 가맹 심사 뒤에 나온다 — 그 전에는 가짜 서버로만 시험된다
- 키워드: 네이버페이, 네이버페이 결제, 간편 결제, 토스 없이 결제 / naverpay, naver pay, korean easy pay, payment without toss

### `payment-paddle` — Paddle 해외 결제(판매 대행) — 서명된 웹훅으로 결제 · 구독 상태를 장부에 옮기고, 결제 · 갱신은 Paddle 이 한다. 구독 해지 · 플랜 올리기는 Paddle API.

- 종류 · 상태: module · experimental — 위치 `modules/payment-paddle`
- 켜는 법: `--modules payment-paddle`
- 의존 한 줄: `implementation(project(":modules:payment-paddle"))`
- 자동으로 따라온다: `crypto`, `payment`
- 설정 접두사 `skeleton.payment-paddle` — 키와 기본값 `docs/config/modules/payment-paddle.yml`
- 비밀 · 환경변수: `<P>_PAYMENT_PADDLE_ENABLED=true`, `<P>_PAYMENT_PADDLE_API_KEY`, `<P>_PAYMENT_PADDLE_CLIENT_TOKEN`, `<P>_PAYMENT_PADDLE_WEBHOOK_SECRET` — 빠지면: 세 비밀 중 하나라도 비면 이 제공자 빈만 없다 — 서버는 뜬다
- 문서: `docs/modules/payment-paddle.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - payment 없이는 쓰지 않는다(따라온다)
  - 환불 요청(adjustment) · 플랜 올리기 · 일시정지는 아직 없다
  - 상품 · 가격은 Paddle 에서 만들고 설정에 가격 id 를 적는다
- 키워드: 패들, 해외 결제, 해외 구독, 다통화, 플랜 변경 / paddle, merchant of record, international subscription, multi-currency, plan change

### `payment-stripe` — Stripe 결제 제공자 — 결제 승인 · 취소를 payment 의 제공자로 구현한다.

- 종류 · 상태: module · experimental — 위치 `modules/payment-stripe`
- 켜는 법: `--modules payment-stripe`
- 의존 한 줄: `implementation(project(":modules:payment-stripe"))`
- 자동으로 따라온다: `crypto`, `payment`
- 설정 접두사 `skeleton.payment-stripe` — 키와 기본값 `docs/config/modules/payment-stripe.yml`
- 비밀 · 환경변수: `<P>_PAYMENT_STRIPE_ENABLED=true`, `<P>_PAYMENT_STRIPE_SECRET_KEY` — 빠지면: 제공자 빈이 없다 — 결제 라우팅이 그 제공자를 못 찾는다
- 문서: `docs/modules/payment-stripe.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - payment 없이는 쓰지 않는다(따라온다)
  - 웹훅 서명 검증 · 주문 처리는 앱 코드
  - 프런트 패키지는 Stripe 리다이렉트 변환이 없다(토스만)
- 키워드: 스트라이프 결제, 해외 결제, 카드 결제 / stripe, stripe payments, card payment, international payment

### `payment-toss` — Toss 결제 제공자 — 결제 승인 · 취소를 payment 의 제공자로 구현한다.

- 종류 · 상태: module · experimental — 위치 `modules/payment-toss`
- 켜는 법: `--modules payment-toss`
- 의존 한 줄: `implementation(project(":modules:payment-toss"))`
- 자동으로 따라온다: `crypto`, `payment`
- 설정 접두사 `skeleton.payment-toss` — 키와 기본값 `docs/config/modules/payment-toss.yml`
- 비밀 · 환경변수: `<P>_PAYMENT_TOSS_ENABLED=true`, `<P>_PAYMENT_TOSS_SECRET_KEY` — 빠지면: 제공자 빈이 없다 — 결제 라우팅이 그 제공자를 못 찾는다. 시크릿 키가 비면 보호 환경(stage · prod)에서는 기동 실패, 그 밖의 환경은 경고
- 문서: `docs/modules/payment-toss.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - payment 없이는 쓰지 않는다(따라온다)
  - 웹훅 서명 검증 · 주문 처리는 앱 코드
- 키워드: 토스 결제, 토스페이먼츠, 카드 결제, 간편 결제 / toss payments, toss, card payment

### `persistence-jdbc` — Spring Data JDBC 의 audit 타임스탬프 콜백과 DB 방언 전략(SqlDialect · SqlDialectVerifier), DB 에 못 닿는 기동 실패를 읽을 수 있는 메시지로 바꾸는 분석기와 첫 연결을 기다리는 선택 기능.

- 종류 · 상태: module · stable — 위치 `modules/persistence-jdbc`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:persistence-jdbc"))`
- 설정 접두사 `skeleton.persistence-jdbc.startup-wait` — 키와 기본값 `docs/config/modules/persistence-jdbc.yml`
- 문서: `docs/modules/persistence-jdbc.md`
- 쓰지 않는 경우:
  - JPA · jOOQ 와 병행하지 않는다 — 하나를 고른다(persistence-jpa | persistence-jooq)
- 키워드: JDBC, Data JDBC, audit 시각, 생성일 수정일, DB 접근 / jdbc, spring data jdbc, audit timestamps, created at updated at

### `persistence-jooq` — jOOQ 연결 — audit 리스너 · MySQL UTC Instant 변환기, DDL 파일에서 코드 생성(빌드에 DB 불필요).

- 종류 · 상태: module · stable — 위치 `modules/persistence-jooq`
- 켜는 법: `--modules persistence-jooq`
- 의존 한 줄: `implementation(project(":modules:persistence-jooq"))`
- 문서: `docs/modules/persistence-jooq.md`, `docs/persistence-jooq.md`
- 쓰지 않는 경우:
  - main 에는 런타임 부품뿐 — 코드 생성 레시피는 docs/persistence-jooq.md
  - JPA · Data JDBC 와 병행하지 않는다
- 키워드: jOOQ, 타입 안전 SQL, 쿼리 빌더 / jooq, type-safe sql, query builder, code generation

### `persistence-jpa` — JPA 엔티티의 created_at · updated_at 자동 채움과 부분 수정 · fetch graph 도우미.

- 종류 · 상태: module · stable — 위치 `modules/persistence-jpa`
- 켜는 법: `--modules persistence-jpa`
- 의존 한 줄: `implementation(project(":modules:persistence-jpa"))`
- 문서: `docs/modules/persistence-jpa.md`, `docs/persistence-jpa.md`
- 쓰지 않는 경우:
  - JDBC · jOOQ 와 병행하지 않는다 — 하나를 고른다
  - JPA 스타터와 DataSource 가 필요하다
- 키워드: JPA, 하이버네이트, 엔티티 audit, ORM / jpa, hibernate, entity audit, orm

### `platform` — 모든 모듈의 공용 기반 — 표준 응답/에러 봉투 · 전역 예외 처리(필드 오류 FieldValidationException) · 모듈끼리 만나는 계약(AuthorDirectory · AccountErasureListener) · trace id · 요청 로깅 · CORS · rate limit · 외부 HTTP 클라이언트 · OpenAPI · 배포 가드 · 한 프로세스에 프로젝트 여럿(skeleton.projects: Host 로 프로젝트 판정 · 프로젝트별 스키마/풀/로그인 설정 · @ProjectApi — docs/multi-project.md).

- 종류 · 상태: module · stable — 위치 `modules/platform`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:platform"))`
- 설정 접두사 `skeleton.authz`, `skeleton.config.validation`, `skeleton.deploy`, `skeleton.http`, `skeleton.observability.links`, `skeleton.openapi`, `skeleton.redaction`, `skeleton.web` — 키와 기본값 `docs/config/modules/platform.yml`
- 문서: `docs/modules/platform.md`, `docs/errors.md`, `docs/logging.md`, `docs/openapi.md`, `docs/external-http.md`, `docs/client-ip.md`, `docs/configuration.md`, `docs/observability-links.md`, `docs/deploy.md`, `docs/multi-project.md`
- 짝 프런트(react-skeleton): 항목 `api-client` · 패키지 `@skeleton/api-client` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 벤더 연동(결제 · 메일 · 저장소 · 소셜 로그인)은 여기에 없다 — 각 모듈
  - 스타터가 이미 가진다 — 따로 켜는 줄이 없다
- 키워드: 에러 응답, 표준 응답, 예외 처리, 요청 로깅, 트레이스, CORS, 요청 제한, 외부 API 호출, 스웨거, 배포 가드, 프로젝트 여럿, 멀티 프로젝트, 도메인별 서비스 / error response, exception handling, request logging, trace id, cors, rate limit, http client, openapi, swagger, deploy guard, multi project, multi tenant host

### `point` — 포인트 원장 — 적립(유효기간 선택) · 소멸이 가까운 묶음부터 차감 · 하루 한 번 소멸 · 운영자 조정. 보상 지급기(RewardIssuer, 종류 point)를 내 이벤트가 「포인트 n점」을 줄 수 있고, 같은 지급 건 · 같은 주문을 두 번 처리해도 한 번만 반영되며 잔액보다 큰 차감은 거절된다. 포인트를 쓰는 길은 서버 API(PointWallet.spend)뿐이고, 내 잔액 · 내역 읽기(/api/v1/points)와 운영 조정(/api/v1/admin/points, 권한 point.manage, 기본 꺼짐 skeleton.point.admin.enabled)이 있다. 저장소는 point-jdbc.

- 종류 · 상태: module · experimental — 위치 `modules/point`
- 켜는 법: `--modules point`
- 의존 한 줄: `implementation(project(":modules:point"))`
- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): `scheduler`
- 설정 접두사 `skeleton.point` — 키와 기본값 `docs/config/modules/point.yml`
- HTTP 경로: `/api/v1/admin/points`, `/api/v1/points`
- 문서: `docs/modules/point.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - 저장소가 없다 — point-jdbc 를 같이 고른다(다른 DB 는 PointStore 를 앱이 구현)
  - 포인트 결제 차감(결제 금액에서 깎기)은 이 판에 없다 — 서버 API PointWallet.spend 를 앱이 부른다
  - 유효기간 기본값이 없다 — 보상 설정(expiresInDays) · 운영자 조정에서 고른다
  - 이벤트(출석 · 친구초대)는 campaign 이 한다 — 이 모듈은 줄 수 있는 보상 한 종류일 뿐
- 키워드: 포인트, 적립금, 포인트 적립, 포인트 소멸, 포인트 차감, 포인트 조정 / points, loyalty points, point ledger, reward points, point expiry

### `point-jdbc` — 포인트 저장소 — PostgreSQL · MySQL 로 point 의 저장 포트를 구현한다: 계정마다 잔액 한 줄(음수 불가)과 원장 표(적립 묶음의 남은 양 · 차감 · 소멸 · 조정, 같은 출처 키는 한 줄). 같은 계정의 쓰기는 잔액 줄을 잠가 직렬로 돌아 동시 차감이 같은 잔액을 두 번 쓰지 못한다.

- 종류 · 상태: module · experimental — 위치 `modules/point-jdbc`
- 켜는 법: `--modules point-jdbc`
- 의존 한 줄: `implementation(project(":modules:point-jdbc"))`
- 자동으로 따라온다: `point`, `scheduler`
- 문서: `docs/modules/point-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - point 없이는 쓰지 않는다(따라온다)
  - 다른 DB(JPA · jOOQ)로 저장하려면 point 의 저장 포트(PointStore)를 앱이 구현한다
- 키워드: 포인트 저장소, 포인트 원장 표 / point storage, point ledger tables

### `redis-cache` — 이름 있는 Redis 캐시(TTL · 접두사) · 안정적인 키 생성기 · 캐시 오류 정책(기본 FAIL_OPEN).

- 종류 · 상태: module · stable — 위치 `modules/redis-cache`
- 켜는 법: `--modules redis-cache`
- 의존 한 줄: `implementation(project(":modules:redis-cache"))`
- 자동으로 따라온다: `redis-core`
- 설정 접두사 `skeleton.redis-cache` — 키와 기본값 `docs/config/modules/redis-cache.yml`
- 문서: `docs/modules/redis-cache.md`
- 쓰지 않는 경우:
  - redis-core 없이는 쓰지 않는다(따라온다)
  - Redis 서버가 필요하다(없어도 앱은 뜨고 캐시는 건너뛴다)
- 키워드: 캐시, Redis 캐시, 캐싱, 응답 캐시 / cache, redis cache, caching, ttl

### `redis-core` — Redis 연결 · StringRedisTemplate · JSON 템플릿 · 키 접두사 — 다른 redis-* 모듈의 바탕.

- 종류 · 상태: module · stable — 위치 `modules/redis-core`
- 켜는 법: `--modules redis-core`
- 의존 한 줄: `implementation(project(":modules:redis-core"))`
- 설정 접두사 `skeleton.redis` — 키와 기본값 `docs/config/modules/redis-core.yml`
- 비밀 · 환경변수: `<P>_REDIS_HOST`, `<P>_REDIS_PORT`, `<P>_REDIS_SSL_ENABLED`, `<P>_REDIS_KEY_PREFIX`, `<P>_REDIS_PASSWORD` (배포 플랫폼이 만들어 넣는다) — 빠지면: 배포 선언의 redis: true 면 플랫폼이 넣는다 — redis: false 인데 redis-* 를 쓰면 첫 명령에서 실패
- 문서: `docs/modules/redis-core.md`
- 쓰지 않는 경우:
  - 연결은 지연이라 Redis 가 없어도 앱은 뜬다 — 첫 명령에서 실패
  - 배포 선언에 redis: true 를 두면 플랫폼이 접속 정보를 넣는다
- 키워드: Redis, 레디스, 키 접두사 / redis, redis connection, key prefix

### `redis-lock` — Redisson 기반 분산 락 — @DistributedLock 어노테이션과 락 실행기.

- 종류 · 상태: module · stable — 위치 `modules/redis-lock`
- 켜는 법: `--modules redis-lock`
- 의존 한 줄: `implementation(project(":modules:redis-lock"))`
- 자동으로 따라온다: `redis-core`
- 설정 접두사 `skeleton.redis-lock` — 키와 기본값 `docs/config/modules/redis-lock.yml`
- 문서: `docs/modules/redis-lock.md`
- 쓰지 않는 경우:
  - redis-core 없이는 쓰지 않는다(따라온다)
  - Redis 서버가 없으면 @DistributedLock 이 즉시 실패한다
  - 단일 인스턴스 스케줄러는 락 없이 된다(scheduler 의 no-op 락)
- 키워드: 분산 락, 락, 동시 실행 방지, 중복 실행 방지 / distributed lock, lock, mutex, redisson

### `redis-rate-limit` — platform 의 rate limit 저장소를 Redis 고정 윈도 카운터로 바꾼다(여러 인스턴스에서 한도 공유).

- 종류 · 상태: module · stable — 위치 `modules/redis-rate-limit`
- 켜는 법: `--modules redis-rate-limit`
- 의존 한 줄: `implementation(project(":modules:redis-rate-limit"))`
- 자동으로 따라온다: `redis-core`
- 설정 접두사 `skeleton.redis-rate-limit` — 키와 기본값 `docs/config/modules/redis-rate-limit.yml`
- 문서: `docs/modules/redis-rate-limit.md`
- 쓰지 않는 경우:
  - skeleton.web.rate-limit.enabled=true 일 때만 쓰인다
  - 단일 인스턴스면 platform 의 인메모리 저장소로 충분하다
- 키워드: 요청 제한, 속도 제한, API 호출 제한, 도배 방지 / rate limit, throttling, request limit, redis rate limit

### `review` — 리뷰 · 평점 — 아무 대상(종류 + id)에 별점 1~5 · 본문, 계정×대상 하나(고쳐 쓴다) · 대상별 평균/분포(원자적 집계) · 정렬/거르기 · 도움돼요 · 구매 인증(앱이 ReviewPurchases.record 로 신호를 준다) · 구매자만 옵션 · 신고 · 운영 큐 · 숨김/되살리기/문제없음 · 자동 가림(옵션) · 운영자 답글 · 사진(저장 키 + 서명 주소 302) · 이벤트 고리(쓰기 트랜잭션 안, 보상은 앱이 잇는다) · 비로그인 읽기(앱이 켠다) · 탈퇴. 상품 · 결제 · 보상을 모른다.

- 종류 · 상태: module · experimental — 위치 `modules/review`
- 켜는 법: `--modules review,review-jdbc`
- 의존 한 줄: `implementation(project(":modules:review"))`
- 자동으로 따라온다: `storage`
- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): `notification`
- 함께 골라야 한다: `review-jdbc`
- 설정 접두사 `skeleton.review` — 키와 기본값 `docs/config/modules/review.yml`
- HTTP 경로: `/api/v1/admin/reviews`, `/api/v1/reviews`
- 문서: `docs/modules/review.md`
- 짝 프런트(react-skeleton): 항목 `reviews`, `reviews-admin` · 패키지 `@skeleton/reviews` · 조각 `--packages reviews`
- 쓰지 않는 경우:
  - review 는 저장소를 모른다 — review-jdbc 를 같이 고른다
  - 대상 종류는 앱이 skeleton.review.target-types 에 적어야 쓸 수 있다 — 기본은 비어 있다
  - 구매 확인은 앱이 ReviewPurchases.record 로 신호를 줘야 한다 — 모듈은 결제를 모른다
  - 보상(포인트 · 업적)은 하지 않는다 — 앱이 ReviewEventListener 에서 잇는다
  - 댓글 · 대화가 아니다 — 대상당 계정 하나, 별점이 있다. 글에 달리는 댓글은 board
- 키워드: 리뷰, 후기, 별점, 평점, 구매 인증, 도움돼요, 상품 후기 / review, rating, star rating, verified purchase, helpful, product reviews

### `review-jdbc` — 리뷰 · 대상별 집계 · 도움돼요 · 구매 신호를 DB 에 둔다 (PostgreSQL · MySQL) — 집계는 원자적 UPDATE, 새 줄은 insertIgnore 먼저(틈 잠금 교착 없음), 목록 질의는 공개 줄만 읽는다.

- 종류 · 상태: module · experimental — 위치 `modules/review-jdbc`
- 켜는 법: `--modules review-jdbc`
- 의존 한 줄: `implementation(project(":modules:review-jdbc"))`
- 자동으로 따라온다: `notification`, `review`, `storage`
- 설정 접두사 없음
- 문서: `docs/modules/review-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `reviews`, `reviews-admin` · 패키지 `@skeleton/reviews` · 조각 `--packages reviews`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 리뷰 저장소, 리뷰 DB / review storage, review repository

### `reward` — 보상 지급의 허리 — 이벤트가 기록한 지급 건을 일꾼이 쿠폰 · 포인트 지급기로 주고(재시도 · 멱등), 받았어요 알림과 운영자 다시 지급 · 취소까지.

- 종류 · 상태: module · experimental — 위치 `modules/reward`
- 켜는 법: `--modules reward,reward-jdbc`
- 의존 한 줄: `implementation(project(":modules:reward"))`
- 자동으로 따라온다: `json`
- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): `notification`
- 함께 골라야 한다: `reward-jdbc`
- 설정 접두사 `skeleton.reward` — 키와 기본값 `docs/config/modules/reward.yml`
- HTTP 경로: `/api/v1/admin/reward-grants`
- 문서: `docs/modules/reward.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - reward 는 저장소를 모른다 — reward-jdbc 를 같이 고른다
  - 지급기(쿠폰 · 포인트)는 별도 모듈이다 — 이 모듈만으로는 아무것도 주지 못한다
  - 조건 판정(출석 · 친구초대)은 이벤트 모듈의 몫
- 키워드: 보상 지급, 지급 건, 쿠폰 지급, 포인트 지급, 다시 지급, 받았어요 알림 / reward grant, reward issuer, payout worker, retry grant

### `reward-jdbc` — 지급 건 저장과 지급 작업을 DB 에 두고 일꾼을 job-queue-jdbc 에 얹는다 (PostgreSQL · MySQL).

- 종류 · 상태: module · experimental — 위치 `modules/reward-jdbc`
- 켜는 법: `--modules reward-jdbc`
- 의존 한 줄: `implementation(project(":modules:reward-jdbc"))`
- 자동으로 따라온다: `json`, `notification`, `reward`
- 설정 접두사 없음
- 문서: `docs/modules/reward-jdbc.md`
- 짝 프런트(react-skeleton): 항목 `rewards`, `rewards-admin` · 패키지 `@skeleton/rewards` · 조각 `--packages rewards`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈과 job-queue-jdbc 스키마, 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 지급 건 저장, 지급 작업 큐 / reward grant store, reward job

### `scheduler` — 어노테이션 기반 스케줄러 — 실행 가드 · 락 매니저 · 실패 핸들러(기본 락은 no-op, 단일 인스턴스용).

- 종류 · 상태: module · stable — 위치 `modules/scheduler`
- 켜는 법: `--modules scheduler`
- 의존 한 줄: `implementation(project(":modules:scheduler"))`
- 설정 접두사 `skeleton.scheduler` — 키와 기본값 `docs/config/modules/scheduler.yml`
- 문서: `docs/modules/scheduler.md`
- 쓰지 않는 경우:
  - 여러 인스턴스에서 한 번만 돌리려면 redis-lock 같은 락 매니저로 바꾼다
  - 재시도 · 영속 큐가 아니다(job-queue-jdbc)
- 키워드: 스케줄러, 정기 작업, 크론, 주기 실행, 배치 / scheduler, cron, scheduled job, periodic task, batch

### `search` — DB 전문 검색 — 다른 모듈이 밀어 넣은 문서(상품 · 노트 …)를 한국어 2글자 조각으로 찾는다(PostgreSQL tsvector · MySQL ngram, 확장 설치 없음). 질의(쪽 · 종류 · 범위 · 정렬 · 하이라이트 위치) · 주인 문서 가리기 · 재색인 작업 · 탈퇴. 밀어 넣기 포트는 platform 의 SearchIndexer 라 다른 모듈이 search 를 모른다.

- 종류 · 상태: module · experimental — 위치 `modules/search`
- 켜는 법: `--modules search,search-jdbc`
- 의존 한 줄: `implementation(project(":modules:search"))`
- 함께 골라야 한다: `search-jdbc`
- 설정 접두사 `skeleton.search` — 키와 기본값 `docs/config/modules/search.yml`
- HTTP 경로: `/api/v1/admin`, `/api/v1/search`
- 문서: `docs/modules/search.md`
- 짝 프런트(react-skeleton): 항목 `search` · 패키지 `@skeleton/search` · 조각 `--packages search`
- 쓰지 않는 경우:
  - search 는 엔진을 모른다 — search-jdbc 를 같이 고른다
  - 형태소 분석 · 동의어 · 오타 교정 · 자동 완성은 없다 — 2글자 조각의 부분 문자열 일치뿐이다(필요하면 바깥 엔진 어댑터를 따로)
  - 원본 상태(판매 중지 · 숨김)를 따라가지 않는다 — 밀어 넣는 쪽이 delete 한다
  - 비로그인 읽기는 앱이 켠다 — 켜면 주인 없는 문서만 보인다
- 키워드: 검색, 전문 검색, 한국어 검색, 부분 일치, 하이라이트, 재색인 / search, full-text search, korean search, ngram, highlight, reindex

### `search-jdbc` — 검색 색인을 DB 에 둔다 (PostgreSQL tsvector + GIN · MySQL ngram FULLTEXT) — 쓰기는 호출자 트랜잭션에 합류, 재색인은 job-queue-jdbc 작업. MySQL 마이그레이션이 불용어를 꺼서 영어 조각이 빠지지 않게 한다.

- 종류 · 상태: module · experimental — 위치 `modules/search-jdbc`
- 켜는 법: `--modules search-jdbc`
- 의존 한 줄: `implementation(project(":modules:search-jdbc"))`
- 자동으로 따라온다: `search`
- 설정 접두사 없음
- 문서: `docs/modules/search-jdbc.md`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈 · job-queue-jdbc 와 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
  - MySQL 에서 색인을 다른 세션이 다시 만들면 불용어가 다시 켜진다 — 같은 SET 을 먼저 한다
- 키워드: 검색 저장소, 검색 색인 DB / search storage, search index database

### `storage` — 저장소 계약(ObjectStorage · PresignedStorage) · 파일 크기/확장자/content-type 검증 + 브라우저 직접 업로드 HTTP(/api/v1/storage: presign · 멀티파트).

- 종류 · 상태: module · stable — 위치 `modules/storage`
- 켜는 법: `--modules storage,storage-s3`
- 의존 한 줄: `implementation(project(":modules:storage"))`
- 자동으로 따라온다: `crypto`
- 함께 골라야 한다: `storage-s3`
- 설정 접두사 `skeleton.storage` — 키와 기본값 `docs/config/modules/storage.yml`
- HTTP 경로: `/api/v1/storage`
- 비밀 · 환경변수: `<P>_STORAGE_WEB_KEY_SECRET` — 빠지면: key-owner=OPAQUE 인데 비었거나 32자보다 짧으면 기동 실패 · 기본(ACCOUNT_ID)에서는 쓰지 않는다 · 무작위로 충분하지만 바꾸면 발급한 OPAQUE 키가 맞지 않게 된다
- 비밀 · 환경변수: `<P>_STORAGE_WEB_PUBLIC_PURGE_CLOUDFLARE_API_TOKEN` — 빠지면: purge.cloudflare.enabled=true(공개 객체를 지운 뒤 CDN 캐시 비우기) 인데 비면 기동 실패 · 기본(꺼짐)에서는 쓰지 않는다 · Cloudflare API 토큰(Zone -> Cache Purge 권한만)이고 운영자가 정한다(.local.env)
- 비밀 · 환경변수: `<P>_STORAGE_WEB_PUBLIC_SECRET` — 빠지면: public.enabled=true(게시판 사진 공개 영역) 인데 비었거나 32자보다 짧으면 기동 실패(base-url 도 필요) · 기본(꺼짐)에서는 쓰지 않는다 · 무작위로 충분하지만 바꾸면 공개 영역 사진의 주소가 달라진다 · 프로젝트마다 달라야 한다
- 비밀 · 환경변수: `<P>_STORAGE_WEB_GUEST_SECRET` — 빠지면: guest.enabled=true(비회원 업로드) 인데 비었거나 32자보다 짧으면 기동 실패 · 기본(꺼짐)에서는 쓰지 않는다 · 무작위로 충분하지만 바꾸면 발급했지만 아직 안 붙은 비회원 키가 맞지 않게 된다 · 프로젝트마다 달라야 한다
- 문서: `docs/modules/storage.md`
- 짝 프런트(react-skeleton): 항목 `storage` · 패키지 `@skeleton/storage` · 조각 `--packages storage`
- 쓰지 않는 경우:
  - 실제 저장소는 어댑터(storage-s3)가 구현한다 — 함께 고른다
  - PresignedStorage 빈이 있어야(= 버킷 설정) HTTP 길이 열린다
  - 이미지 변환 · 썸네일은 없다
- 키워드: 파일 업로드, 이미지 업로드, 첨부파일, 프리사인, 파일 검증 / file upload, image upload, attachment, presigned url, multipart upload

### `storage-s3` — S3 호환 저장소(AWS S3 · Cloudflare R2 · MinIO) — presign PUT/GET · 멀티파트 · 복사 · 일괄 삭제, 공개 URL RAW | OPAQUE.

- 종류 · 상태: module · stable — 위치 `modules/storage-s3`
- 켜는 법: `--modules storage-s3`
- 의존 한 줄: `implementation(project(":modules:storage-s3"))`
- 자동으로 따라온다: `storage`
- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): `crypto`
- 설정 접두사 `skeleton.storage-s3` — 키와 기본값 `docs/config/modules/storage-s3.yml`
- 비밀 · 환경변수: `<P>_STORAGE_S3_BUCKET`, `<P>_STORAGE_S3_ENDPOINT_OVERRIDE`, `<P>_STORAGE_S3_CREDENTIALS_ACCESS_KEY_ID`, `<P>_STORAGE_S3_CREDENTIALS_SECRET_ACCESS_KEY` — 빠지면: 버킷이 비면 저장소 빈이 없다 · 키가 비면 AWS 기본 자격 증명 체인으로 가서 첫 사용에서 실패(기동은 된다)
- 문서: `docs/modules/storage-s3.md`, `docs/storage-s3.md`
- 짝 프런트(react-skeleton): 항목 `storage` · 패키지 `@skeleton/storage` · 조각 `--packages storage`
- 쓰지 않는 경우:
  - storage 없이는 쓰지 않는다(따라온다)
  - OPAQUE 공개 URL 만 crypto 가 필요하다(컴파일 전용 — 앱이 crypto 를 더해야 한다)
  - 버킷이 없으면 클라이언트만 있고 저장소 빈은 없다
- 키워드: S3, R2, 클라우드 스토리지, 파일 저장소, 이미지 저장 / s3, r2, cloudflare r2, minio, object storage

### `time` — 글로벌 시간 — 요청마다 뷰어 시간대 · 로케일 · ZonedMoment(미래 현지 시각) · 사람이 읽는 이중 포맷 · 국가 → 시간대.

- 종류 · 상태: module · stable — 위치 `modules/time`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:time"))`
- 설정 접두사 `skeleton.time` — 키와 기본값 `docs/config/modules/time.yml`
- 문서: `docs/modules/time.md`, `docs/time.md`
- 짝 프런트(react-skeleton): 항목 `time` · 패키지 `@skeleton/time` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - Instant · LocalDate · ZonedMoment 를 구분하는 규칙 — ZoneId.systemDefault() · TIMESTAMP 칼럼 · 맨 LocalDateTime 은 쓰지 않는다(docs/time.md)
  - 스타터가 이미 가진다
- 키워드: 시간대, 타임존, 날짜 표시, 현지 시간, 예약 시각, 글로벌 / timezone, time zone, locale, zoned moment, date format, global time

### `workspace` — 워크스페이스(팀 · 조직) — 만들기 · 소유자/관리자/멤버 역할 · 주소(slug, 바꿔도 옛 주소가 따라온다) · 멤버 관리 · 소유권 이전 · 메일 링크 초대(만료 · 재발송 · 취소 · 확인된 이메일로만 수락, 켜야 한다), 그리고 요청 헤더 `X-Workspace-Id` 로 앱 데이터를 묶는 경계 `CurrentWorkspace`. 좌석 상한은 앱이 정한다. 운영자 읽기 경로(목록 · 검색 · 상세, `workspace.read`)는 꺼져 있다가 앱이 켠다.

- 종류 · 상태: module · experimental — 위치 `modules/workspace`
- 켜는 법: `--modules workspace,workspace-jdbc`
- 의존 한 줄: `implementation(project(":modules:workspace"))`
- 함께 골라야 한다: `workspace-jdbc`
- 설정 접두사 `skeleton.workspace` — 키와 기본값 `docs/config/modules/workspace.yml`
- HTTP 경로: `/api/v1/admin`, `/api/v1/workspaces`, `/api/v1/workspace-invitations`
- 비밀 · 환경변수: `<P>_WORKSPACE_MAIL_LINK_BASE_URL` — 빠지면: 초대 메일 링크가 여는 프론트 주소 — 비밀 아님(선언의 env:). 초대를 켠 앱만; 비면 보호 환경(<P>_ENV=stage|prod)에서 기동 실패
- 문서: `docs/modules/workspace.md`
- 짝 프런트(react-skeleton): 항목 `workspace`, `workspace-admin` · 패키지 `@skeleton/workspace` · 조각 `--packages workspace`
- 쓰지 않는 경우:
  - workspace 는 저장소를 모른다 — workspace-jdbc 를 같이 고른다
  - 서비스 운영자 권한(authz)을 대신하지 않는다 — 워크스페이스 역할은 그 팀 안에서만 뜻이 있다
  - 기존 모듈(board · storage · notification …)의 데이터에 경계를 걸어 주지 않는다 — 앱 컨트롤러가 CurrentWorkspace 로 직접 건다
  - 구독 · 결제를 팀 단위로 바꾸지 않는다 — 좌석 상한 계약(WorkspaceSeatPolicy)만 있고 값은 앱이 정한다
- 키워드: 워크스페이스, 팀, 조직, 멤버 역할, 소유권 이전, 멀티 테넌트 / workspace, team, organization, tenant, member roles, ownership transfer

### `workspace-jdbc` — 워크스페이스 · slug 기록 · 멤버 · 초대를 DB 에 둔다 (PostgreSQL · MySQL) — 소유자는 유니크 표시로 늘 하나, 모든 변경은 워크스페이스 줄 잠금 아래에서 현재 역할로 판정.

- 종류 · 상태: module · experimental — 위치 `modules/workspace-jdbc`
- 켜는 법: `--modules workspace-jdbc`
- 의존 한 줄: `implementation(project(":modules:workspace-jdbc"))`
- 자동으로 따라온다: `workspace`
- 설정 접두사 없음
- 문서: `docs/modules/workspace-jdbc.md`
- 쓰지 않는 경우:
  - DataSource · db-* 모듈과 이 모듈의 마이그레이션이 적용된 스키마가 필요하다
- 키워드: 워크스페이스 저장소, 팀 DB / workspace storage, workspace repository

### `app-api` — 스타터 — 새 프로젝트가 복사해 시작하는 최소 조립(platform · auth · persistence-jdbc · db-postgresql · migration-flyway · time)과 HelloController 하나. Redis · Kafka · S3 · 메일 없이 부팅한다.

- 종류 · 상태: app · stable — 위치 `apps/api`
- 켜는 법: 스타터(apps/api)에 기본 포함
- HTTP 경로: `/api/v1`, `/api/v1/project-hello`
- 문서: `docs/minimal-composition.md`
- 짝 프런트(react-skeleton): 항목 `app-starter`, `app-starter-ssr` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 모든 모듈을 보여 주는 데모가 아니다(apps/workbench)
  - 제품 코드는 모듈 내부가 아니라 앱 패키지(루트 패키지의 app.api 하위)에 둔다
- 키워드: 스타터, 시작 템플릿, 새 앱, 최소 구성, 기본 앱 / starter, boilerplate, minimal app, new app, template

### `app-sample` — 참조 앱 Notes — 로그인한 사람이 첨부 있는 노트를 관리하는 제품 모양의 작은 앱(스타터 + idempotency · notification-jdbc/sse · storage-s3 · job-queue-jdbc · board · author-profile · alert-jdbc · search-jdbc 의 노트·상품 검색 · review-jdbc 의 상품 리뷰(산 사람만, 결제 → 구매 신호, 리뷰 → 보상) · payment-jdbc/payment-toss 의 결제 「응원하기」). 로그인 아이디를 켜고 이메일은 쓰기 때 받는다. 새 기능은 이 앱의 한 조각을 따라 한다.

- 종류 · 상태: app · stable — 위치 `apps/sample`
- 켜는 법: `--with-sample`
- 자동으로 따라온다: `alert-jdbc`, `api-key`, `api-key-jdbc`, `auth-magic-link`, `auth-social-google`, `auth-social-kakao`, `auth-social-naver`, `auth-social-oidc`, `auth-social-x`, `author-profile`, `author-profile-jdbc`, `authz`, `authz-jdbc`, `badge`, `badge-jdbc`, `board`, `board-jdbc`, `campaign`, `campaign-jdbc`, `catalog`, `catalog-jdbc`, `coupon`, `coupon-jdbc`, `crypto`, `dashboard`, `dashboard-cloudflare`, `dashboard-ga`, `dashboard-jdbc`, `json`, `notification`, `notification-jdbc`, `notification-sse`, `payment`, `payment-jdbc`, `payment-kakaopay`, `payment-naverpay`, `payment-paddle`, `payment-toss`, `point`, `point-jdbc`, `review`, `review-jdbc`, `reward`, `reward-jdbc`, `scheduler`, `search`, `search-jdbc`, `storage`, `storage-s3`, `workspace`, `workspace-jdbc`
- HTTP 경로: `/api/v1/cheers`, `/api/v1/notes`
- 문서: `docs/sample.md`
- 짝 프런트(react-skeleton): 항목 `app-sample` · 조각 `--with-sample`
- 쓰지 않는 경우:
  - 복사 대상이 아니라 따라 하는 참조 — 쓰지 않을 기능은 지운다
  - PostgreSQL 전용이다(--db mysql 과 함께 쓸 수 없다)
- 키워드: 참조 앱, 예제 앱, 샘플, 노트 앱, 제품 모양 예시, 결제 예제, 응원하기 / sample app, reference app, example, notes app, payment example, cheer

### `app-workbench` — 데모 — 모든 모듈을 함께 얹고 샘플 엔드포인트(/api/v1/skeleton · /api/v1/examples)와 통합 테스트로 모듈이 공존함을 증명한다(react-skeleton 워크벤치 화면의 백엔드).

- 종류 · 상태: app · stable — 위치 `apps/workbench`
- 켜는 법: `--with-workbench`
- 자동으로 따라온다: `alert-jdbc`, `api-key`, `api-key-jdbc`, `async`, `async-notification`, `auth-magic-link`, `auth-social-google`, `auth-social-kakao`, `auth-social-naver`, `auth-social-oidc`, `auth-social-x`, `author-profile`, `author-profile-jdbc`, `authz`, `authz-jdbc`, `badge`, `badge-jdbc`, `board`, `board-jdbc`, `campaign`, `campaign-jdbc`, `catalog`, `catalog-jdbc`, `config-aws-ssm`, `coupon`, `coupon-jdbc`, `crypto`, `dashboard`, `dashboard-cloudflare`, `dashboard-ga`, `dashboard-jdbc`, `db-mysql`, `event-kafka`, `json`, `notification`, `notification-jdbc`, `notification-slack`, `notification-sse`, `notification-websocket`, `payment`, `payment-jdbc`, `payment-kakaopay`, `payment-naverpay`, `payment-paddle`, `payment-stripe`, `payment-toss`, `persistence-jooq`, `persistence-jpa`, `point`, `point-jdbc`, `redis-cache`, `redis-core`, `redis-lock`, `redis-rate-limit`, `review`, `review-jdbc`, `reward`, `reward-jdbc`, `scheduler`, `search`, `search-jdbc`, `storage`, `storage-s3`, `workspace`, `workspace-jdbc`
- HTTP 경로: `/api/v1`, `/api/v1/examples`, `/api/v1/skeleton`, `/api/v1/skeleton/enums`, `/api/v1/skeleton/json`, `/api/v1/skeleton/payments`, `/api/v1/skeleton/polymorphic/contents`
- 문서: `docs/minimal-composition.md`, `docs/modules/README.md`
- 짝 프런트(react-skeleton): 항목 `app-workbench` · 조각 `--with-workbench`
- 쓰지 않는 경우:
  - 제품에 복사하지 않는다
  - 모든 모듈이 남고 PostgreSQL 전용이다(--db mysql 과 함께 쓸 수 없다)
  - Dockerfile 이 이 앱의 이미지 빌드를 거부한다
- 키워드: 워크벤치, 모듈 데모, 전체 모듈, 모든 모듈 함께 / workbench, demo, all modules, module smoke test

### `script-build-capabilities` — capabilities.json 에서 docs/capabilities.md · llms.txt 를 생성하고(--check 로 어긋남 검사) 카탈로그를 정본 형식으로 정리한다.

- 종류 · 상태: script · stable — 위치 `scripts/build-capabilities.pl`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/capabilities.md`, `README.md`
- 쓰지 않는 경우:
  - 항목 검증(레포의 실제와 맞는가)은 modules/platform 의 Capabilities*Test — 이 스크립트는 생성만 한다
- 키워드: 기능 카탈로그, 카탈로그 생성, 카탈로그 점검, 기능 목록 / capabilities catalog, generate docs, catalog check, llms.txt

### `script-check-real-providers` — 진짜 소셜 로그인 · 진짜 메일 · 진짜 토스 시험 전 점검 — .env.local 의 모양, 제공자 서버가 client id/secret 을 받아 주는지(가짜 코드로 한 번), 떠 있는 백엔드의 /auth/methods, SMTP 설정 · DNS(SPF/DMARC), 요청하면 시험 메일 한 통, 토스 테스트 키가 있으면 결제 장부가 기대는 응답(없는 결제의 조회 · 취소 · 멱등 키 · 응답 시간)을 돈이 움직이지 않는 호출 여섯 번(테스트 코드 헤더를 붙인 취소 포함)으로 ✓/✗ 줄로 말한다.

- 종류 · 상태: script · stable — 위치 `scripts/check-real-providers.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/real-provider-setup.md`
- 쓰지 않는 경우:
  - 제공자 화면에서 사람이 동의를 누르는 구간은 확인하지 못한다 (docs/real-provider-setup.md 의 시험 순서)
  - 가짜 서버로 이 스크립트 자신을 시험하려면 --self-test
- 키워드: 소셜 로그인 점검, 메일 발송 점검, 구글 카카오 키 확인, 시험 메일, 토스 키 점검 / check social login keys, smtp test mail, real provider readiness, toss test key check

### `script-convert-ids-to-sortable` — 일회용 — 이미 있는 PostgreSQL DB 의 옛 행 id(접두사 + 32자 16진수 · UUID v4)를 시간순 id(접두사 + ULID · UUIDv7)로 바꾼다: pg_dump 백업 → 한 트랜잭션에서 id 를 가리키는 모든 문자열 칸을 같이 변환 → 행 수 · 옛 형식 0건 · 외래 키 검사(--dry-run 은 끝에서 되돌린다).

- 종류 · 상태: script · stable — 위치 `scripts/convert-ids-to-sortable.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/schema-management.md`
- 쓰지 않는 경우:
  - 새 DB 에는 필요 없다 — 새 id 는 처음부터 시간순이다(SortableIds)
  - MySQL 은 지원하지 않는다(로컬에 데이터가 없어 만들지 않았다)
  - 이미 결제사에 결제된 주문이 있는 운영 DB 에는 쓰지 않는다 — 주문 id 는 결제사의 orderId 라 바꾸면 조회가 어긋난다
- 키워드: id 변환, 시간순 id, ULID 변환, 기존 데이터 id 바꾸기 / convert ids, sortable ids, ULID migration, rewrite primary keys

### `script-dev` — 로컬 풀스택 한 줄 실행 — DB(+ 로컬 S3) 컨테이너를 올리고 백엔드를 띄운다. 옆의 ../web 프런트가 있으면 같이 띄운다.

- 종류 · 상태: script · stable — 위치 `scripts/dev.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/minimal-composition.md`
- 쓰지 않는 경우:
  - 로컬 개발용이다 — 운영 배포는 deploy/app.yaml 선언(docs/deploy.md)
  - Docker 가 필요하다
- 키워드: 로컬 실행, 개발 서버, 한 줄 실행, DB 띄우기 / run locally, dev server, local stack, start database

### `script-dev-multi` — 한 프로세스에 프로젝트 둘(a.localhost · b.localhost) 견본을 로컬에서 띄운다 — Docker Postgres 에 프로젝트 계정 · 스키마(scripts/project-account.sql, 멱등)를 만들고 multi 프로필로 apps/api 를 기동한다.

- 종류 · 상태: script · stable — 위치 `scripts/dev-multi.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/multi-project.md`
- 쓰지 않는 경우:
  - 한 서비스로 띄울 때는 scripts/dev.sh
  - 운영 DB 계정은 docs/multi-project.md 의 SQL 로 만든다
- 키워드: 프로젝트 여럿 로컬 실행, 멀티 프로젝트 견본 / multi project local run, multi tenant sample

### `script-dev-sample` — 샘플 앱 Notes 풀스택 한 줄 실행 — DB + 로컬 S3 → 백엔드(apps/sample) → 짝 프런트(react-skeleton 의 apps/sample).

- 종류 · 상태: script · stable — 위치 `scripts/dev-sample.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/sample.md`
- 쓰지 않는 경우:
  - --with-sample 로 찍은 프로젝트에서만 남는다
  - 얇은 래퍼다 — 하는 일은 scripts/dev.sh
- 키워드: 샘플 실행, 노트 앱 실행 / run sample, notes app

### `script-migrations-lock` — 동결된 Flyway 마이그레이션의 잠금(migrations.lock: 경로 + sha256 + 업그레이드 시험의 baseline)을 검사하고(--check) 새 파일만 더한다 — 기존 줄의 변경은 --rewrite <경로> 로만.

- 종류 · 상태: script · stable — 위치 `scripts/migrations-lock.pl`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/schema-management.md`
- 쓰지 않는 경우:
  - 이미 배포된 마이그레이션을 고치는 용도가 아니다 — 새 V 파일을 더한다 (./gradlew newMigration)
  - new-project.sh 가 찍은 프로젝트의 첫 잠금은 --regenerate 로 스스로 만든다 — 손으로 쓰지 않는다
- 키워드: 마이그레이션 잠금, 마이그레이션 동결, 체크섬 불일치, 마이그레이션 수정 금지 / migration lock, frozen migrations, flyway checksum, migrations.lock

### `script-native-build` — GraalVM 25 공식 Docker 이미지 안에서 앱을 네이티브 실행 파일로 만든다(선택 빌드 -Pnative, 기본 JVM 빌드는 그대로) — 결과 apps/<앱>/build/native/nativeCompile/app.

- 종류 · 상태: script · stable — 위치 `scripts/native-build.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/native-image.md`
- 쓰지 않는 경우:
  - 기본 배포 이미지(Dockerfile)를 만들지 않는다 — 네이티브 배포는 Dockerfile.native
- 키워드: 네이티브 이미지, 네이티브 빌드, GraalVM, 콜드스타트 / native image, graalvm build, cold start

### `script-native-smoke` — 떠 있는 tools/native-check 서버에 가입 · 로그인 · 갱신 · 가짜 결제사 결제 · 구독 · 작업 큐(깨우기 주소) · 웹훅을 실제 HTTP 로 돌려 본다.

- 종류 · 상태: script · stable — 위치 `scripts/native-smoke.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/native-image.md`
- 쓰지 않는 경우:
  - 서버를 띄우거나 빌드하지 않는다 — scripts/native-test.sh 가 한다
- 키워드: 네이티브 시험, 핵심 흐름 시험 / native smoke test, core flow check

### `script-native-smoke-full` — 네이티브 --full 시험: 가짜 상대(메일 · 로컬 S3 · 소셜 5종 · 토스 · Paddle)를 붙여 메일 · 파일 저장 · 소셜 로그인 · 결제사 · SSE 흐름을 실제 HTTP 로 돌린다.

- 종류 · 상태: script · stable — 위치 `scripts/native-smoke-full.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/native-image.md`
- 쓰지 않는 경우:
  - 서버를 띄우지 않는다 — scripts/native-test.sh --full 이 한다
  - 스켈레톤 레포 전용(새 프로젝트에는 복사되지 않는다)
- 키워드: 네이티브 전체 시험, 소셜 로그인 시험 / native full test, social login check

### `script-native-test` — 앱을 네이티브로 빌드해 새 DB 와 함께 컨테이너에서 띄우고 흐름 시험(--smoke)을 돌린다 — 스켈레톤은 tools/native-check + native-smoke.sh, 새 프로젝트는 apps/api + 자기 시험(릴리스 전 · 의존성 파일 PR 에서 돌리는 것을 권한다).

- 종류 · 상태: script · stable — 위치 `scripts/native-test.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/native-image.md`
- 쓰지 않는 경우:
  - 평소 CI 가 아니다 — 매 PR 에서 돌지 않는다(.github/workflows/native.yml)
- 키워드: 네이티브 시험, 릴리스 전 시험 / native test, release check

### `script-new-project` — 새 프로젝트 한 줄 찍기 — 이 레포를 복사해 고른 모듈 · 앱만 남기고, 이름 · 접두사를 바꾸고, 배포 선언 · 설정 블록 · 이 카탈로그를 걸러 다시 쓴다(--dry-run 은 고른 모듈과 따라온 이유만 보인다).

- 종류 · 상태: script · stable — 위치 `scripts/new-project.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/minimal-composition.md`, `docs/new-project-recipe.md`
- 쓰지 않는 경우:
  - 찍은 프로젝트에는 따라가지 않는다(스켈레톤 도구)
  - 기존 프로젝트에 모듈을 더하는 도구가 아니다 — apps/api 에 의존 한 줄
  - 프런트는 react-skeleton 의 같은 이름 스크립트
- 키워드: 새 프로젝트, 프로젝트 만들기, 찍어내기, 스캐폴딩, 프로젝트 시작 / new project, scaffold, stamp, template, project generator

### `script-payment-webhook` — 결제 웹훅을 손으로 넣어 본다 — 토스 PAYMENT_STATUS_CHANGED 모양의 본문을 떠 있는 서버의 수신함 주소로 보내 주소의 비밀 조각 · 재전송 중복 · 모르는 주문 · 본문 불신(서버가 결제사에 다시 조회)을 확인한다. 대상 서버는 늘 인자로 받는다.

- 종류 · 상태: script · stable — 위치 `scripts/payment-webhook.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/real-provider-setup.md`
- 쓰지 않는 경우:
  - 토스가 보내는 진짜 웹훅의 모양은 확인하지 못한다 — 본문 모양은 가정이다 (docs/real-provider-setup.md 의 검증 기록)
  - 서버가 처리한 결과는 이 스크립트가 보지 못한다 — 서버 로그의 'payment webhook result=' 줄과 운영자 수신함을 본다
- 키워드: 웹훅 보내 보기, 결제 웹훅 시험, 수신함 시험, 토스 웹훅 / send test webhook, payment webhook check, toss webhook

### `script-rename-skeleton` — 패키지 · 설정 접두사 · 클래스 이름 · 환경변수 접두사를 한 번에 바꾸는 스크립트(GitHub Template 로 만든 뒤 이름만 바꿀 때) — 끝에 남은 흔적을 검사하고 이 카탈로그를 다시 만든다.

- 종류 · 상태: script · stable — 위치 `scripts/rename-skeleton.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/minimal-composition.md`
- 쓰지 않는 경우:
  - 모듈을 고르지 않는다 — 모듈 가지치기까지 하는 것은 new-project.sh
  - 샘플 API 경로 /api/v1/skeleton 은 바꾸지 않는다
- 키워드: 이름 바꾸기, 리네임, 패키지 변경, 템플릿 이름 바꾸기 / rename, change package, rename template, project name

### `script-sample-e2e-backend` — 샘플 앱 백엔드를 e2e 테스트용으로 올리고 내리는 비대화형 스크립트(react-skeleton 의 Playwright e2e 가 부른다).

- 종류 · 상태: script · stable — 위치 `scripts/sample-e2e-backend.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/sample.md`
- 쓰지 않는 경우:
  - --with-sample 로 찍은 프로젝트에서만 남는다
  - 자기 개발 컨테이너와 섞이지 않게 전용 compose 프로젝트 이름을 쓴다
- 키워드: e2e 백엔드, e2e 테스트용 서버 / e2e backend, playwright backend

### `script-test-deploy-contract` — 홈서버 배포 계약(docs/deploy.md)을 진짜 컨테이너로 증명한다 — 이미지 빌드 · 헬스체크 · 0.0.0.0 · stdout 로그 · 보호 환경 가드 실패 화면.

- 종류 · 상태: script · stable — 위치 `scripts/test-deploy-contract.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/deploy.md`
- 쓰지 않는 경우:
  - Docker 가 필요하다
  - 배포 자체를 하지 않는다 — 계약을 지키는지 증명만 한다
- 키워드: 배포 검증, 배포 계약 테스트, 컨테이너 검증 / deploy contract test, container check, image verification

### `script-test-new-project` — new-project.sh 의 테스트 — 빠른 검사(./gradlew check 가 부른다)와 --full(여러 조합과 레시피의 예제 명령을 정말 찍어 ./gradlew build).

- 종류 · 상태: script · stable — 위치 `scripts/test-new-project.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/minimal-composition.md`, `docs/new-project-recipe.md`
- 쓰지 않는 경우:
  - --full 은 Docker(Testcontainers)가 필요하고 수 분~수십 분 걸린다
  - 찍은 프로젝트에는 따라가지 않는다
- 키워드: 찍기 테스트, 조합 검증, 스캐폴딩 테스트 / stamp test, combination test, scaffold test

