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
| 게시판 · 글쓰기 · 댓글 · 대댓글 · 공감(반응) | `board` + `board-jdbc` | `--modules board,board-jdbc` | `--packages board` — react `board` | 게시판 코드 만들기(skeleton.board.seed-boards 설정이나 운영자 API) · 반응 종류(skeleton.board.reaction.types) · 운영자 역할(MODERATOR). notification 을 같이 고르면 댓글 알림이 기본으로 켜진다. 목록 · 상세 · 글쓰기 화면은 프런트. |
| 알림 (종 · 목록 · 안 읽은 수) | `notification` + `notification-jdbc` | `--modules notification,notification-jdbc` | `--packages notifications` — react `notifications` | 알림을 만드는 쪽(NotificationPublisher.publish)은 앱 코드 — 받는 사람은 NotificationEvent.recipientIds. 프런트에서는 NotificationBell 배치와 알림 API 인스턴스. |
| 알림이 새로고침 없이 즉시 뜬다 (실시간) | `notification` + `notification-jdbc` + (`notification-sse` \| `notification-websocket`) | `--modules notification,notification-jdbc,notification-sse` — `notification-sse` \| `notification-websocket` 중 하나 이상 고른다 | `--packages notifications,realtime` — react `live-notifications` | SSE(단방향, 더 단순) 또는 WebSocket(양방향) 중 고른다. 둘 다 프로세스 안 브로커라 인스턴스가 여럿이면 다른 인스턴스의 알림을 못 받는다. 프런트 연결 훅. |
| 파일 업로드 (이미지 · 첨부) | `storage` + `storage-s3` | `--modules storage,storage-s3` | `--packages storage` — react `storage` | 버킷 · 키(환경변수), 업로드 규칙(skeleton.storage.validation — 확장자 · 크기), 업로드한 파일 키를 도메인에 저장하는 코드(앱). 로컬 개발은 compose 의 S3 를 scripts/dev.sh 가 올린다. |
| 결제 (토스 · 스트라이프) | `payment` + (`payment-toss` \| `payment-stripe`) | `--modules payment,payment-toss` — `payment-toss` \| `payment-stripe` 중 하나 이상 고른다 | `--packages payment` — react `payment` | HTTP 엔드포인트가 없다 — 주문 · 금액 검증 컨트롤러 · 성공/실패 리다이렉트 처리 · 웹훅 서명 검증을 앱이 쓴다(PaymentService.confirm 은 받은 금액을 그대로 넘긴다). enabled=true + secret-key(환경변수). 중복 결제 방지에는 idempotency. |
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
| 이용약관 · 개인정보 처리방침 · 가입 동의 기록 · 새 판 재동의 · 마케팅 수신 동의(선택, 철회) | `legal` + `legal-jdbc` | (덧붙일 것 없음) | (기본 포함) — react `legal-documents` `cookie-consent` | 스타터에 기본 포함(legal-jdbc) — 문서는 모듈의 TEMPLATE(ko/en)이라 stage · prod 에서는 기동이 거부된다: src/main/resources/legal/ 에 자기 문서와 manifest.json 을 두고(검토 끝난 판은 REVIEWED + sha256), skeleton.legal.facts.* 를 채운다. 시험 배포는 <P>_LEGAL_ACKNOWLEDGE_TEMPLATE=true. MySQL 은 log_bin_trust_function_creators=1. 동의 화면 · 가입 폼 체크박스는 프런트(docs/legal-http-contract.md). |

## 전체 목록

### 모듈 (`modules/*`)

| id | 요약 | 켜는 조각 | 키워드 (한국어 / 영어) | 짝 프런트 |
|---|---|---|---|---|
| `account` | 계정 수명주기 — 이메일 · 비밀번호 가입과 이메일 확인, 비밀번호 재설정 · 변경, 이메일 변경, 소셜 연결(자동 병합 없음), 탈퇴(다시 인증 → 유예 → 삭제), 로그인 시도 제한, 관리자 도구, 첫 관리자, ko/en 메일 템플릿. auth 가 진짜 계정으로 로그인하게 한다. | 스타터(apps/api)에 기본 포함 | 회원가입, 가입, 이메일 인증, 비밀번호 재설정, 비밀번호 찾기, 이메일 변경, 계정 삭제, 탈퇴, 계정 관리, 관리자 도구, 계정 정지, 프로필 / sign up, registration, email verification, password reset, forgot password, change email, delete account, account management, admin tools, suspend account, profile | `@skeleton/auth` |
| `account-jdbc` | account 의 저장소 · 토큰 · 감사 기록을 PostgreSQL · MySQL 로 — 스키마는 모듈 마이그레이션(MySQL 은 이메일을 utf8mb4_bin 으로 정확 일치). 보호 환경에서 메모리 저장소를 막는 가드를 통과하는 방법. | 스타터(apps/api)에 기본 포함 | 계정 저장, 계정 DB, 회원 테이블, 계정 마이그레이션 / account storage, accounts table, account persistence, account migration | `@skeleton/auth` |
| `alert` | 주인 경보 — 5xx 몰림 · 기동 실패 · 죽은 작업을 Discord 호환 웹훅(+ 선택적 메일)으로 알린다. 에러 수집(Sentry)의 답. | 스타터(apps/api)에 기본 포함 | 경보, 주인 알림, 에러 알림, 장애 알림, 디스코드 알림, 에러 수집, 5xx 알림 / owner alert, error reporting, discord webhook, incident alert, 5xx alert, sentry alternative | — |
| `alert-jdbc` | 경보 기록을 DB 에 두어 여러 인스턴스 · 재시작을 가로질러 같은 경보를 접는다 (PostgreSQL · MySQL). | `--modules alert-jdbc` | 경보 기록, 경보 중복 접기, 경보 DB / alert ledger, alert dedupe, alert database | — |
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
| `board` | 게시판 — 글 · 중첩 댓글(대댓글) · 설정으로 늘리는 반응(좋아요 · 공감 …) · 운영자 숨김/고정 · 댓글 알림, HTTP /api/v1/boards 까지. | `--modules board,board-jdbc` | 게시판, 커뮤니티, 글쓰기, 댓글, 대댓글, 공감, 좋아요, 반응, 운영자 숨김, 공지 고정 / board, forum, community, post, comment, reply, reaction, like, moderation | `@skeleton/board` |
| `board-jdbc` | 게시판 저장소 — PostgreSQL · MySQL 로 board 의 저장 포트 네 개를 구현한다(skeleton_board* 테이블 · 원자적 카운터). | `--modules board-jdbc` | 게시판 저장소, 게시판 DB / board storage, board repository, board database | `@skeleton/board` |
| `captcha-turnstile` | 봇 방지 — Cloudflare Turnstile 토큰을 서버에서 검증한다(TurnstileVerifier). | 스타터(apps/api)에 기본 포함 | 캡차, 봇 방지, 스팸 방지, 로봇 확인, 가입 폼 보호 / captcha, turnstile, bot protection, spam protection, cloudflare turnstile | `@skeleton/captcha-turnstile` |
| `config-aws-ssm` | AWS SSM Parameter Store 의 값을 시작할 때 스프링 프로퍼티로 불러온다. | `--modules config-aws-ssm` | AWS 설정, 파라미터 스토어, SSM, 비밀 불러오기 / aws ssm, parameter store, remote config, aws secrets | — |
| `crypto` | AES-GCM 텍스트 암호화 · 키 회전용 키 id 봉투 · URL 에 안전한 불투명 토큰 · 선택적 영속 변환기. | `--modules crypto` | 암호화, 복호화, 비밀 저장, 불투명 URL, 키 회전 / encryption, aes gcm, opaque token, key rotation, encrypt at rest | — |
| `db-mysql` | MySQL 방언 — 드라이버 · Flyway MySQL 지원 · SqlDialect · Data JDBC 시간 변환, 세션을 UTC 로 고정한다. | `--db mysql` | MySQL, 마이SQL, DB 변경, MySQL 로 바꾸기 / mysql, mariadb, use mysql, switch database | — |
| `db-postgresql` | PostgreSQL 방언(기본) — 드라이버 · Flyway PostgreSQL 지원 · SqlDialect · Data JDBC 시간 변환. | 스타터(apps/api)에 기본 포함 | PostgreSQL, 포스트그레스, DB 방언, 데이터베이스 / postgresql, postgres, database dialect, database | — |
| `event-kafka` | 이벤트를 Kafka 로 발행하는 KafkaEventPublisher 와 키 · 메시지 · 직렬화 전략(켜기 전에는 로그만 남긴다). | `--modules event-kafka` | 카프카, 이벤트 발행, 메시지 큐, 이벤트 스트림 / kafka, event publishing, message broker, event stream | — |
| `idempotency` | Idempotency-Key 헤더로 명령 요청(결제 · 생성)의 중복 실행을 막고 첫 응답을 재생한다. | 스타터(apps/api)에 기본 포함 | 멱등, 중복 요청 방지, 두 번 눌러도 한 번, 멱등 키, 중복 결제 방지 / idempotency, idempotency key, duplicate request, replay response | — |
| `job-queue-jdbc` | DB 테이블 기반 재시도 작업 큐 — 백오프 · DEAD 처리 · FOR UPDATE SKIP LOCKED, Redis 없이 여러 인스턴스에서 안전. | 스타터(apps/api)에 기본 포함 | 작업 큐, 백그라운드 작업, 재시도, 비동기 작업, 내보내기 작업, 잡 큐 / job queue, background job, retry, dead letter, task queue | — |
| `json` | JsonCodec · 임의 JSON 을 담는 JsonDocument · 버전 있는 페이로드 · JPA/JDBC 변환기. | `--modules json` | JSON, JSON 칼럼, 버전 있는 페이로드, 직렬화 / json, json column, versioned payload, serialization | — |
| `legal` | 약관 · 개인정보 처리방침 · 동의 기록 — 종류 · 판(시행일 · DRAFT/REVIEWED) · 원문 해시를 못 박는 장부 · 가입 동의(코드 확인 때 계정과 같은 트랜잭션에 기록) · 선택 동의 철회 · 새 판 재동의(403) · 계정 삭제 때 익명화, HTTP /api/v1/legal 까지. 문서는 앱이 싣고 예시 TEMPLATE 은 prod 에서 막는다. | 스타터(apps/api)에 기본 포함 | 약관, 이용약관, 개인정보 처리방침, 동의, 재동의, 마케팅 수신 동의, 동의 기록, 약관 버전, 법적 문서, 동의 철회 / terms of service, privacy policy, consent, re-consent, marketing consent, consent record, document version, legal documents, withdraw consent | — |
| `legal-jdbc` | 약관 · 동의 저장소 — PostgreSQL · MySQL 로 legal 의 저장 포트 둘(동의 사건 · 판 장부)을 구현한다(legal_consents · legal_document_versions, 트리거로 더하기만). | 스타터(apps/api)에 기본 포함 | 동의 저장소, 약관 DB / consent storage, consent repository, legal database | — |
| `migration` | 마이그레이션 공통 규칙 — DB 를 지우는 설정이 허용 프로필 밖에서 켜지면 시작을 막는 가드(도구 중립). | 스타터(apps/api)에 기본 포함 | 마이그레이션 가드, DB 초기화 방지, 스키마 보호 / migration guard, prevent db wipe, schema safety | — |
| `migration-flyway` | Flyway 구현 — 로컬 clean 옵트인 · 마이그레이션 파일 이름 규칙(V<UTC 14자리>__snake_case.sql) 검사. | 스타터(apps/api)에 기본 포함 | Flyway, 마이그레이션, DB 스키마 버전, 스키마 변경 / flyway, database migration, schema migration, schema version | — |
| `notification` | 알림 계약 · 수신자 해석 · 인메모리 브로커 + 로그인한 사람의 받은편지함 HTTP(/api/v1/notifications: 목록 · 읽음 · 모두 읽음). | `--modules notification` | 알림, 알림 목록, 받은편지함, 안 읽은 알림, 알림 읽음, 알림 보내기 / notification, inbox, unread, mark as read, publish notification | `@skeleton/notifications` |
| `notification-jdbc` | 알림 받은편지함을 DB 테이블에 저장한다(skeleton_notification_inbox). | `--modules notification-jdbc` | 알림 저장, 알림 DB, 알림 기록 유지 / notification storage, persist notifications, inbox database | `@skeleton/notifications` |
| `notification-mail` | SMTP 로 메일을 보내는 MailSender (spring.mail.* 위에). | 스타터(apps/api)에 기본 포함 | 메일 발송, 이메일 보내기, SMTP, 메일 / send email, smtp, mail sender, email | — |
| `notification-slack` | 알림과 @SlackException 예외 알림을 Slack 웹훅으로 보낸다. | `--modules notification-slack` | 슬랙 알림, 슬랙, 예외 알림 / slack notification, slack webhook, exception alert | — |
| `notification-sse` | 알림을 Server-Sent Events 로 브라우저에 실시간 전달한다(GET /api/v1/notifications/sse) — 받는 사람이 정해진 알림은 그 사람에게만. | `--modules notification-sse` | 실시간 알림, SSE, 알림 즉시 표시, 푸시 알림, 서버 푸시 / realtime notification, sse, server-sent events, push notification | `@skeleton/realtime` |
| `notification-websocket` | 알림을 STOMP over WebSocket(/ws/notifications)으로 전달한다 — 인증 · trace 인터셉터 · 하트비트를 갖춘 인프로세스 브로커. | `--modules notification-websocket` | 웹소켓, 실시간 알림, 양방향 통신, STOMP / websocket, stomp, realtime notification, two-way | `@skeleton/realtime` |
| `payment` | 결제 계약 — PaymentService · PaymentProviderRouter(제공자는 payment-toss \| payment-stripe). 모듈이 HTTP 를 열지 않는다. | `--modules payment` — `payment-toss` \| `payment-stripe` 중 하나 이상 고른다 | 결제, 결제 승인, 환불, 결제 취소, 유료 / payment, checkout, refund, payment confirm, paid | `@skeleton/payment` |
| `payment-stripe` | Stripe 결제 제공자 — 결제 승인 · 취소를 payment 의 제공자로 구현한다. | `--modules payment-stripe` | 스트라이프 결제, 해외 결제, 카드 결제 / stripe, stripe payments, card payment, international payment | `@skeleton/payment` |
| `payment-toss` | Toss 결제 제공자 — 결제 승인 · 취소를 payment 의 제공자로 구현한다. | `--modules payment-toss` | 토스 결제, 토스페이먼츠, 카드 결제, 간편 결제 / toss payments, toss, card payment | `@skeleton/payment` |
| `persistence-jdbc` | Spring Data JDBC 의 audit 타임스탬프 콜백과 DB 방언 전략(SqlDialect · SqlDialectVerifier). | 스타터(apps/api)에 기본 포함 | JDBC, Data JDBC, audit 시각, 생성일 수정일, DB 접근 / jdbc, spring data jdbc, audit timestamps, created at updated at | — |
| `persistence-jooq` | jOOQ 연결 — audit 리스너 · MySQL UTC Instant 변환기, DDL 파일에서 코드 생성(빌드에 DB 불필요). | `--modules persistence-jooq` | jOOQ, 타입 안전 SQL, 쿼리 빌더 / jooq, type-safe sql, query builder, code generation | — |
| `persistence-jpa` | JPA 엔티티의 created_at · updated_at 자동 채움과 부분 수정 · fetch graph 도우미. | `--modules persistence-jpa` | JPA, 하이버네이트, 엔티티 audit, ORM / jpa, hibernate, entity audit, orm | — |
| `platform` | 모든 모듈의 공용 기반 — 표준 응답/에러 봉투 · 전역 예외 처리 · trace id · 요청 로깅 · CORS · rate limit · 외부 HTTP 클라이언트 · OpenAPI · 배포 가드. | 스타터(apps/api)에 기본 포함 | 에러 응답, 표준 응답, 예외 처리, 요청 로깅, 트레이스, CORS, 요청 제한, 외부 API 호출, 스웨거, 배포 가드 / error response, exception handling, request logging, trace id, cors, rate limit, http client, openapi, swagger, deploy guard | `@skeleton/api-client` |
| `redis-cache` | 이름 있는 Redis 캐시(TTL · 접두사) · 안정적인 키 생성기 · 캐시 오류 정책(기본 FAIL_OPEN). | `--modules redis-cache` | 캐시, Redis 캐시, 캐싱, 응답 캐시 / cache, redis cache, caching, ttl | — |
| `redis-core` | Redis 연결 · StringRedisTemplate · JSON 템플릿 · 키 접두사 — 다른 redis-* 모듈의 바탕. | `--modules redis-core` | Redis, 레디스, 키 접두사 / redis, redis connection, key prefix | — |
| `redis-lock` | Redisson 기반 분산 락 — @DistributedLock 어노테이션과 락 실행기. | `--modules redis-lock` | 분산 락, 락, 동시 실행 방지, 중복 실행 방지 / distributed lock, lock, mutex, redisson | — |
| `redis-rate-limit` | platform 의 rate limit 저장소를 Redis 고정 윈도 카운터로 바꾼다(여러 인스턴스에서 한도 공유). | `--modules redis-rate-limit` | 요청 제한, 속도 제한, API 호출 제한, 도배 방지 / rate limit, throttling, request limit, redis rate limit | — |
| `scheduler` | 어노테이션 기반 스케줄러 — 실행 가드 · 락 매니저 · 실패 핸들러(기본 락은 no-op, 단일 인스턴스용). | `--modules scheduler` | 스케줄러, 정기 작업, 크론, 주기 실행, 배치 / scheduler, cron, scheduled job, periodic task, batch | — |
| `storage` | 저장소 계약(ObjectStorage · PresignedStorage) · 파일 크기/확장자/content-type 검증 + 브라우저 직접 업로드 HTTP(/api/v1/storage: presign · 멀티파트). | `--modules storage,storage-s3` | 파일 업로드, 이미지 업로드, 첨부파일, 프리사인, 파일 검증 / file upload, image upload, attachment, presigned url, multipart upload | `@skeleton/storage` |
| `storage-s3` | S3 호환 저장소(AWS S3 · Cloudflare R2 · MinIO) — presign PUT/GET · 멀티파트 · 복사 · 일괄 삭제, 공개 URL RAW \| OPAQUE. | `--modules storage-s3` | S3, R2, 클라우드 스토리지, 파일 저장소, 이미지 저장 / s3, r2, cloudflare r2, minio, object storage | `@skeleton/storage` |
| `time` | 글로벌 시간 — 요청마다 뷰어 시간대 · 로케일 · ZonedMoment(미래 현지 시각) · 사람이 읽는 이중 포맷 · 국가 → 시간대. | 스타터(apps/api)에 기본 포함 | 시간대, 타임존, 날짜 표시, 현지 시간, 예약 시각, 글로벌 / timezone, time zone, locale, zoned moment, date format, global time | `@skeleton/time` |

### 앱 (`apps/*`)

| id | 요약 | 켜는 조각 | 키워드 (한국어 / 영어) | 짝 프런트 |
|---|---|---|---|---|
| `app-api` | 스타터 — 새 프로젝트가 복사해 시작하는 최소 조립(platform · auth · persistence-jdbc · db-postgresql · migration-flyway · time)과 HelloController 하나. Redis · Kafka · S3 · 메일 없이 부팅한다. | 스타터(apps/api)에 기본 포함 | 스타터, 시작 템플릿, 새 앱, 최소 구성, 기본 앱 / starter, boilerplate, minimal app, new app, template |  |
| `app-sample` | 참조 앱 Notes — 로그인한 사람이 첨부 있는 노트를 관리하는 제품 모양의 작은 앱(스타터 + idempotency · notification-jdbc/sse · storage-s3 · job-queue-jdbc · board · alert-jdbc). 새 기능은 이 앱의 한 조각을 따라 한다. | `--with-sample` | 참조 앱, 예제 앱, 샘플, 노트 앱, 제품 모양 예시 / sample app, reference app, example, notes app |  |
| `app-workbench` | 데모 — 모든 모듈을 함께 얹고 샘플 엔드포인트(/api/v1/skeleton · /api/v1/examples)와 통합 테스트로 모듈이 공존함을 증명한다(react-skeleton 워크벤치 화면의 백엔드). | `--with-workbench` | 워크벤치, 모듈 데모, 전체 모듈, 모든 모듈 함께 / workbench, demo, all modules, module smoke test |  |

### 스크립트 (`scripts/*`)

| id | 요약 | 켜는 조각 | 키워드 (한국어 / 영어) | 짝 프런트 |
|---|---|---|---|---|
| `script-build-capabilities` | capabilities.json 에서 docs/capabilities.md · llms.txt 를 생성하고(--check 로 어긋남 검사) 카탈로그를 정본 형식으로 정리한다. | (도구 — 켜는 조각 없음) | 기능 카탈로그, 카탈로그 생성, 카탈로그 점검, 기능 목록 / capabilities catalog, generate docs, catalog check, llms.txt | — |
| `script-dev` | 로컬 풀스택 한 줄 실행 — DB(+ 로컬 S3) 컨테이너를 올리고 백엔드를 띄운다. 옆의 ../web 프런트가 있으면 같이 띄운다. | (도구 — 켜는 조각 없음) | 로컬 실행, 개발 서버, 한 줄 실행, DB 띄우기 / run locally, dev server, local stack, start database | — |
| `script-dev-sample` | 샘플 앱 Notes 풀스택 한 줄 실행 — DB + 로컬 S3 → 백엔드(apps/sample) → 짝 프런트(react-skeleton 의 apps/sample). | (도구 — 켜는 조각 없음) | 샘플 실행, 노트 앱 실행 / run sample, notes app | — |
| `script-new-project` | 새 프로젝트 한 줄 찍기 — 이 레포를 복사해 고른 모듈 · 앱만 남기고, 이름 · 접두사를 바꾸고, 배포 선언 · 설정 블록 · 이 카탈로그를 걸러 다시 쓴다(--dry-run 은 고른 모듈과 따라온 이유만 보인다). | (도구 — 켜는 조각 없음) | 새 프로젝트, 프로젝트 만들기, 찍어내기, 스캐폴딩, 프로젝트 시작 / new project, scaffold, stamp, template, project generator | — |
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

- kotlin(이 레포): `scripts/new-project.sh ~/work/saas/api dev.example.saas saas Saas --modules payment,payment-toss,notification,notification-jdbc --db mysql`
- react: `scripts/new-project.sh ~/work/saas/web saas --packages marketing,payment,notifications,captcha-turnstile --scope @acme`
- 짝 프런트 항목: `account-lifecycle`, `auth`, `screen-patterns`, `pricing-page`, `payment`, `notifications`, `captcha-turnstile`

### 콘텐츠 · 랜딩 사이트 (서버 렌더링) — 검색에 노출되는 소개 · 요금 · 약관 사이트

> 제품 소개 · 요금제 · 약관을 보여 주는 공개 사이트. 검색 결과와 링크 미리보기가 중요해 서버가 첫 HTML 을 그리고, 한국어 · 영어, 쿠키 동의 배너, 404 화면이 있다. 로그인 · 데이터 입력은 없다.

- kotlin(이 레포): `scripts/new-project.sh ~/work/site/api dev.example.site site Site`
- react: `scripts/new-project.sh ~/work/site/web site --packages marketing,i18n --ssr`
- 짝 프런트 항목: `app-starter-ssr`, `landing-page`, `pricing-page`, `legal-documents`, `cookie-consent`, `error-pages`, `i18n`

그다음의 설정 · 실행 · 검증 · 손으로 써야 하는 것: `docs/new-project-recipe.md` 의 같은 이름 예.

## 항목 상세

### `account` — 계정 수명주기 — 이메일 · 비밀번호 가입과 이메일 확인, 비밀번호 재설정 · 변경, 이메일 변경, 소셜 연결(자동 병합 없음), 탈퇴(다시 인증 → 유예 → 삭제), 로그인 시도 제한, 관리자 도구, 첫 관리자, ko/en 메일 템플릿. auth 가 진짜 계정으로 로그인하게 한다.

- 종류 · 상태: module · experimental — 위치 `modules/account`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:account"))`
- 설정 접두사 `skeleton.account` — 키와 기본값 `docs/config/modules/account.yml`
- HTTP 경로: `/api/v1`, `/api/v1/account`, `/api/v1/account/identities/social`, `/api/v1/admin/accounts`, `/api/v1/auth`
- 비밀 · 환경변수: `<P>_ACCOUNT_MAIL_LINK_BASE_URL` — 빠지면: 메일 링크가 여는 프론트 주소 — 비밀 아님(선언의 env:). 비면 보호 환경(<P>_ENV=stage|prod)에서 기동 실패
- 비밀 · 환경변수: `<P>_ACCOUNT_BOOTSTRAP_ADMIN_EMAIL` — 빠지면: 선택 — 이 이메일이 확인되면 ADMIN 을 준다. 없으면 관리자 API 를 켠 앱은 경고
- 비밀 · 환경변수: `<P>_CLIENT_IP_MODE`, `<P>_CLIENT_IP_TRUSTED_PROXIES` (배포 플랫폼이 만들어 넣는다) — 빠지면: 홈서버 플랫폼이 넣는다(예약 이름). 안 정해지면 보호 환경에서 기동 실패 — IP 한도가 X-Forwarded-For 로 풀린다
- 문서: `docs/modules/account.md`
- 짝 프런트(react-skeleton): 항목 `auth`, `account-lifecycle`, `account-admin` · 패키지 `@skeleton/auth` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 메일 발송 자체는 notification-mail — 보호 환경에서는 메일 발송 길(notification-mail 또는 AccountMailTransport 빈)이 없으면 기동을 거부한다
  - 소셜 제공자 클라이언트는 auth-social-google | -kakao | -naver
  - 리프레시 토큰 · 세션 목록은 auth-session, 링크 로그인은 auth-magic-link
  - 소셜 로그인을 계정으로 이어 주는 것은 켜진 auth-social 과 skeleton.account.social.* 설정(모듈 기본은 가입 · 병합 모두 꺼짐)
- 키워드: 회원가입, 가입, 이메일 인증, 비밀번호 재설정, 비밀번호 찾기, 이메일 변경, 계정 삭제, 탈퇴, 계정 관리, 관리자 도구, 계정 정지, 프로필 / sign up, registration, email verification, password reset, forgot password, change email, delete account, account management, admin tools, suspend account, profile

### `account-jdbc` — account 의 저장소 · 토큰 · 감사 기록을 PostgreSQL · MySQL 로 — 스키마는 모듈 마이그레이션(MySQL 은 이메일을 utf8mb4_bin 으로 정확 일치). 보호 환경에서 메모리 저장소를 막는 가드를 통과하는 방법.

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

### `board` — 게시판 — 글 · 중첩 댓글(대댓글) · 설정으로 늘리는 반응(좋아요 · 공감 …) · 운영자 숨김/고정 · 댓글 알림, HTTP /api/v1/boards 까지.

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

### `board-jdbc` — 게시판 저장소 — PostgreSQL · MySQL 로 board 의 저장 포트 네 개를 구현한다(skeleton_board* 테이블 · 원자적 카운터).

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

### `notification` — 알림 계약 · 수신자 해석 · 인메모리 브로커 + 로그인한 사람의 받은편지함 HTTP(/api/v1/notifications: 목록 · 읽음 · 모두 읽음).

- 종류 · 상태: module · stable — 위치 `modules/notification`
- 켜는 법: `--modules notification`
- 의존 한 줄: `implementation(project(":modules:notification"))`
- 설정 접두사 `skeleton.notification.inbox` — 키와 기본값 `docs/config/modules/notification.yml`
- HTTP 경로: `/api/v1/notifications`
- 문서: `docs/modules/notification.md`
- 짝 프런트(react-skeleton): 항목 `notifications` · 패키지 `@skeleton/notifications` · 조각 `--packages notifications`
- 쓰지 않는 경우:
  - 영속은 notification-jdbc, 실시간 전달은 notification-sse | -websocket, 메일은 notification-mail — 어댑터를 따로 고른다
  - 기본 브로커는 프로세스 안에서만 전달한다
  - 알림을 만드는 쪽(NotificationPublisher.publish)은 앱 코드가 부른다
- 키워드: 알림, 알림 목록, 받은편지함, 안 읽은 알림, 알림 읽음, 알림 보내기 / notification, inbox, unread, mark as read, publish notification

### `notification-jdbc` — 알림 받은편지함을 DB 테이블에 저장한다(skeleton_notification_inbox).

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

### `payment` — 결제 계약 — PaymentService · PaymentProviderRouter(제공자는 payment-toss | payment-stripe). 모듈이 HTTP 를 열지 않는다.

- 종류 · 상태: module · experimental — 위치 `modules/payment`
- 켜는 법: `--modules payment` — `payment-toss` | `payment-stripe` 중 하나 이상 고른다
- 의존 한 줄: `implementation(project(":modules:payment"))`
- 하나 이상 고른다: `payment-toss` | `payment-stripe`
- 설정 접두사 `skeleton.payment` — 키와 기본값 `docs/config/modules/payment.yml`
- 문서: `docs/modules/payment.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - HTTP 엔드포인트가 없다 — 주문 · 금액 검증 · 성공/실패 리다이렉트 컨트롤러는 앱 코드(금액 검증 없는 확정 엔드포인트를 기본값으로 만들지 않으려고)
  - 구독 · 정기 결제 관리는 없다
  - 제공자 모듈 없이는 결제할 수 없다
- 키워드: 결제, 결제 승인, 환불, 결제 취소, 유료 / payment, checkout, refund, payment confirm, paid

### `payment-stripe` — Stripe 결제 제공자 — 결제 승인 · 취소를 payment 의 제공자로 구현한다.

- 종류 · 상태: module · experimental — 위치 `modules/payment-stripe`
- 켜는 법: `--modules payment-stripe`
- 의존 한 줄: `implementation(project(":modules:payment-stripe"))`
- 자동으로 따라온다: `payment`
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
- 자동으로 따라온다: `payment`
- 설정 접두사 `skeleton.payment-toss` — 키와 기본값 `docs/config/modules/payment-toss.yml`
- 비밀 · 환경변수: `<P>_PAYMENT_TOSS_ENABLED=true`, `<P>_PAYMENT_TOSS_SECRET_KEY` — 빠지면: 제공자 빈이 없다 — 결제 라우팅이 그 제공자를 못 찾는다
- 문서: `docs/modules/payment-toss.md`
- 짝 프런트(react-skeleton): 항목 `payment` · 패키지 `@skeleton/payment` · 조각 `--packages payment`
- 쓰지 않는 경우:
  - payment 없이는 쓰지 않는다(따라온다)
  - 웹훅 서명 검증 · 주문 처리는 앱 코드
- 키워드: 토스 결제, 토스페이먼츠, 카드 결제, 간편 결제 / toss payments, toss, card payment

### `persistence-jdbc` — Spring Data JDBC 의 audit 타임스탬프 콜백과 DB 방언 전략(SqlDialect · SqlDialectVerifier).

- 종류 · 상태: module · stable — 위치 `modules/persistence-jdbc`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:persistence-jdbc"))`
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

### `platform` — 모든 모듈의 공용 기반 — 표준 응답/에러 봉투 · 전역 예외 처리 · trace id · 요청 로깅 · CORS · rate limit · 외부 HTTP 클라이언트 · OpenAPI · 배포 가드.

- 종류 · 상태: module · stable — 위치 `modules/platform`
- 켜는 법: 스타터(apps/api)에 기본 포함
- 의존 한 줄: `implementation(project(":modules:platform"))`
- 설정 접두사 `skeleton.config.validation`, `skeleton.deploy`, `skeleton.http`, `skeleton.observability.links`, `skeleton.openapi`, `skeleton.redaction`, `skeleton.web` — 키와 기본값 `docs/config/modules/platform.yml`
- 문서: `docs/modules/platform.md`, `docs/errors.md`, `docs/logging.md`, `docs/openapi.md`, `docs/external-http.md`, `docs/client-ip.md`, `docs/configuration.md`, `docs/observability-links.md`, `docs/deploy.md`
- 짝 프런트(react-skeleton): 항목 `api-client` · 패키지 `@skeleton/api-client` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 벤더 연동(결제 · 메일 · 저장소 · 소셜 로그인)은 여기에 없다 — 각 모듈
  - 스타터가 이미 가진다 — 따로 켜는 줄이 없다
- 키워드: 에러 응답, 표준 응답, 예외 처리, 요청 로깅, 트레이스, CORS, 요청 제한, 외부 API 호출, 스웨거, 배포 가드 / error response, exception handling, request logging, trace id, cors, rate limit, http client, openapi, swagger, deploy guard

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

### `storage` — 저장소 계약(ObjectStorage · PresignedStorage) · 파일 크기/확장자/content-type 검증 + 브라우저 직접 업로드 HTTP(/api/v1/storage: presign · 멀티파트).

- 종류 · 상태: module · stable — 위치 `modules/storage`
- 켜는 법: `--modules storage,storage-s3`
- 의존 한 줄: `implementation(project(":modules:storage"))`
- 자동으로 따라온다: `crypto`
- 함께 골라야 한다: `storage-s3`
- 설정 접두사 `skeleton.storage` — 키와 기본값 `docs/config/modules/storage.yml`
- HTTP 경로: `/api/v1/storage`
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

### `app-api` — 스타터 — 새 프로젝트가 복사해 시작하는 최소 조립(platform · auth · persistence-jdbc · db-postgresql · migration-flyway · time)과 HelloController 하나. Redis · Kafka · S3 · 메일 없이 부팅한다.

- 종류 · 상태: app · stable — 위치 `apps/api`
- 켜는 법: 스타터(apps/api)에 기본 포함
- HTTP 경로: `/api/v1`
- 문서: `docs/minimal-composition.md`
- 짝 프런트(react-skeleton): 항목 `app-starter`, `app-starter-ssr` · 조각 기본 포함(덧붙일 것 없음)
- 쓰지 않는 경우:
  - 모든 모듈을 보여 주는 데모가 아니다(apps/workbench)
  - 제품 코드는 모듈 내부가 아니라 앱 패키지(루트 패키지의 app.api 하위)에 둔다
- 키워드: 스타터, 시작 템플릿, 새 앱, 최소 구성, 기본 앱 / starter, boilerplate, minimal app, new app, template

### `app-sample` — 참조 앱 Notes — 로그인한 사람이 첨부 있는 노트를 관리하는 제품 모양의 작은 앱(스타터 + idempotency · notification-jdbc/sse · storage-s3 · job-queue-jdbc · board · alert-jdbc). 새 기능은 이 앱의 한 조각을 따라 한다.

- 종류 · 상태: app · stable — 위치 `apps/sample`
- 켜는 법: `--with-sample`
- 자동으로 따라온다: `alert-jdbc`, `auth-magic-link`, `board`, `board-jdbc`, `crypto`, `json`, `notification`, `notification-jdbc`, `notification-sse`, `storage`, `storage-s3`
- HTTP 경로: `/api/v1/notes`
- 문서: `docs/sample.md`
- 짝 프런트(react-skeleton): 항목 `app-sample` · 조각 `--with-sample`
- 쓰지 않는 경우:
  - 복사 대상이 아니라 따라 하는 참조 — 쓰지 않을 기능은 지운다
  - PostgreSQL 전용이다(--db mysql 과 함께 쓸 수 없다)
- 키워드: 참조 앱, 예제 앱, 샘플, 노트 앱, 제품 모양 예시 / sample app, reference app, example, notes app

### `app-workbench` — 데모 — 모든 모듈을 함께 얹고 샘플 엔드포인트(/api/v1/skeleton · /api/v1/examples)와 통합 테스트로 모듈이 공존함을 증명한다(react-skeleton 워크벤치 화면의 백엔드).

- 종류 · 상태: app · stable — 위치 `apps/workbench`
- 켜는 법: `--with-workbench`
- 자동으로 따라온다: `alert-jdbc`, `async`, `async-notification`, `auth-magic-link`, `auth-social-google`, `auth-social-kakao`, `auth-social-naver`, `board`, `board-jdbc`, `config-aws-ssm`, `crypto`, `db-mysql`, `event-kafka`, `json`, `notification`, `notification-jdbc`, `notification-slack`, `notification-sse`, `notification-websocket`, `payment`, `payment-stripe`, `payment-toss`, `persistence-jooq`, `persistence-jpa`, `redis-cache`, `redis-core`, `redis-lock`, `redis-rate-limit`, `scheduler`, `storage`, `storage-s3`
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

### `script-dev` — 로컬 풀스택 한 줄 실행 — DB(+ 로컬 S3) 컨테이너를 올리고 백엔드를 띄운다. 옆의 ../web 프런트가 있으면 같이 띄운다.

- 종류 · 상태: script · stable — 위치 `scripts/dev.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/minimal-composition.md`
- 쓰지 않는 경우:
  - 로컬 개발용이다 — 운영 배포는 deploy/app.yaml 선언(docs/deploy.md)
  - Docker 가 필요하다
- 키워드: 로컬 실행, 개발 서버, 한 줄 실행, DB 띄우기 / run locally, dev server, local stack, start database

### `script-dev-sample` — 샘플 앱 Notes 풀스택 한 줄 실행 — DB + 로컬 S3 → 백엔드(apps/sample) → 짝 프런트(react-skeleton 의 apps/sample).

- 종류 · 상태: script · stable — 위치 `scripts/dev-sample.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/sample.md`
- 쓰지 않는 경우:
  - --with-sample 로 찍은 프로젝트에서만 남는다
  - 얇은 래퍼다 — 하는 일은 scripts/dev.sh
- 키워드: 샘플 실행, 노트 앱 실행 / run sample, notes app

### `script-new-project` — 새 프로젝트 한 줄 찍기 — 이 레포를 복사해 고른 모듈 · 앱만 남기고, 이름 · 접두사를 바꾸고, 배포 선언 · 설정 블록 · 이 카탈로그를 걸러 다시 쓴다(--dry-run 은 고른 모듈과 따라온 이유만 보인다).

- 종류 · 상태: script · stable — 위치 `scripts/new-project.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/minimal-composition.md`, `docs/new-project-recipe.md`
- 쓰지 않는 경우:
  - 찍은 프로젝트에는 따라가지 않는다(스켈레톤 도구)
  - 기존 프로젝트에 모듈을 더하는 도구가 아니다 — apps/api 에 의존 한 줄
  - 프런트는 react-skeleton 의 같은 이름 스크립트
- 키워드: 새 프로젝트, 프로젝트 만들기, 찍어내기, 스캐폴딩, 프로젝트 시작 / new project, scaffold, stamp, template, project generator

### `script-rename-skeleton` — 패키지 · 설정 접두사 · 클래스 이름 · 환경변수 접두사를 한 번에 바꾸는 스크립트(GitHub Template 로 만든 뒤 이름만 바꿀 때) — 끝에 남은 흔적을 검사하고 이 카탈로그를 다시 만든다.

- 종류 · 상태: script · stable — 위치 `scripts/rename-skeleton.sh`
- 켜는 법: (도구 — 켜는 조각 없음)
- 문서: `docs/minimal-composition.md`
- 쓰지 않는 경우:
  - 모듈을 고르지 않는다 — 모듈 가지치기까지 하는 것은 new-project.sh
  - 샘플 API 경로 /api/v1/skeleton 과 skeleton_jobs 테이블 이름은 바꾸지 않는다
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

