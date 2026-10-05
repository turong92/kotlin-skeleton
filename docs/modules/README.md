# 모듈 색인

모듈 하나 = 의존성 한 줄. 아래 표에서 고르고, 각 문서에서 켜는 법 · 필요한 것 · 교체 지점을 본다.
모든 문서는 같은 틀이다: 의존성 한 줄 · 함께 오는 모듈 · 설정 접두사 · 기본 동작 · 부팅에 필요한 것 · 교체 지점 · 마이그레이션 · 프론트 짝 · 테스트.
`ModuleDocumentationTest`(`modules/platform`)가 문서와 코드가 어긋나면 빌드를 실패시킨다.

| 모듈 | 하는 일 | 문서 |
|---|---|---|
| `platform` | 공용 웹 기반: 표준 응답·에러 봉투, 예외 처리, trace id, 요청 로깅, CORS · rate limit 정책, 외부 HTTP 클라이언트, 시각 제공자 | [platform](platform.md) |
| `auth` | JWT 로그인 · dev-login · break-glass 와 SecurityFilterChain | [auth](auth.md) |
| `auth-social` | 제공자 중립 소셜 로그인 계약과 엔드포인트 | [auth-social](auth-social.md) |
| `auth-social-google` | Google OAuth 제공자 | [auth-social-google](auth-social-google.md) |
| `auth-social-kakao` | Kakao OAuth 제공자 | [auth-social-kakao](auth-social-kakao.md) |
| `auth-social-naver` | Naver OAuth 제공자 | [auth-social-naver](auth-social-naver.md) |
| `async` | 컨텍스트(trace id · MDC)를 넘기는 @Async 실행기 | [async](async.md) |
| `async-notification` | 비동기 예외를 알림으로 보낸다 | [async-notification](async-notification.md) |
| `notification` | 알림 계약 · 수신자 해석 · 인메모리 브로커 | [notification](notification.md) |
| `notification-jdbc` | 알림 인박스를 DB 테이블에 저장 | [notification-jdbc](notification-jdbc.md) |
| `notification-websocket` | STOMP/WebSocket 알림 전달 | [notification-websocket](notification-websocket.md) |
| `notification-sse` | SSE 알림 전달 | [notification-sse](notification-sse.md) |
| `notification-slack` | Slack 웹훅 알림 · 예외 알림 | [notification-slack](notification-slack.md) |
| `storage` | 저장소 계약 · 파일 검증 | [storage](storage.md) |
| `storage-s3` | S3 / R2 저장소 (presign · 서버 업로드) | [storage-s3](storage-s3.md) |
| `board` | 게시판: 글 · 댓글 트리(대댓글) · 설정으로 늘리는 반응 종류, 운영자 숨김 · 고정, 댓글 알림 | [board](board.md) |
| `board-jdbc` | 게시판 저장소 (PostgreSQL · MySQL) | [board-jdbc](board-jdbc.md) |
| `payment` | 결제 계약 · 제공자 라우팅 | [payment](payment.md) |
| `payment-toss` | Toss 결제 제공자 | [payment-toss](payment-toss.md) |
| `payment-stripe` | Stripe 결제 제공자 | [payment-stripe](payment-stripe.md) |
| `config-aws-ssm` | AWS SSM Parameter Store 를 프로퍼티로 읽기 | [config-aws-ssm](config-aws-ssm.md) |
| `idempotency` | Idempotency-Key 로 명령 엔드포인트 보호 | [idempotency](idempotency.md) |
| `json` | JsonCodec · JsonDocument · 버전 있는 페이로드 | [json](json.md) |
| `crypto` | AES-GCM 텍스트 암호화 · 불투명 URL 토큰 | [crypto](crypto.md) |
| `persistence-jpa` | JPA audit 타임스탬프 · 부분 수정 도우미 | [persistence-jpa](persistence-jpa.md) |
| `persistence-jdbc` | Spring Data JDBC audit · SqlDialect 전략 | [persistence-jdbc](persistence-jdbc.md) |
| `persistence-jooq` | jOOQ 연결 · audit 리스너 · UTC Instant 변환기 | [persistence-jooq](persistence-jooq.md) |
| `redis-core` | Redis 연결 · 템플릿 · 키 접두사 | [redis-core](redis-core.md) |
| `redis-lock` | Redis 분산 락 (@DistributedLock) | [redis-lock](redis-lock.md) |
| `redis-cache` | Redis 캐시 매니저 기본값 | [redis-cache](redis-cache.md) |
| `redis-rate-limit` | Redis 기반 rate limit 저장소 | [redis-rate-limit](redis-rate-limit.md) |
| `scheduler` | 어노테이션 기반 스케줄러 (락 · 실패 처리) | [scheduler](scheduler.md) |
| `time` | 뷰어 시간대 · ZonedMoment · 이중 포맷 | [time](time.md) |
| `event-kafka` | Kafka 이벤트 발행 어댑터 | [event-kafka](event-kafka.md) |
| `job-queue-jdbc` | DB 테이블 재시도 큐 (Redis 없음) | [job-queue-jdbc](job-queue-jdbc.md) |
| `notification-mail` | SMTP 메일 발송 | [notification-mail](notification-mail.md) |
| `alert` | 주인 경보 — 5xx 몰림 · 기동 실패 · 죽은 작업을 Discord 웹훅(+메일)으로 (에러 수집의 답) | [alert](alert.md) |
| `alert-jdbc` | 경보 기록을 DB 에 두어 여러 인스턴스를 가로질러 접기 | [alert-jdbc](alert-jdbc.md) |
| `captcha-turnstile` | Cloudflare Turnstile 토큰 검증 | [captcha-turnstile](captcha-turnstile.md) |
| `db-postgresql` | PostgreSQL 방언 모듈 (db-* 중 정확히 하나) | [db-postgresql](db-postgresql.md) |
| `db-mysql` | MySQL 방언 모듈 (db-* 중 정확히 하나) | [db-mysql](db-mysql.md) |
| `migration` | 마이그레이션 공통 규칙 · DB 초기화 방지 가드 | [migration](migration.md) |
| `migration-flyway` | Flyway 구현: 로컬 clean 옵트인 · 파일 이름 규칙 | [migration-flyway](migration-flyway.md) |

## 읽는 법

- **함께 오는 모듈**: Gradle 이 런타임 클래스패스에 함께 얹는 모듈 전체. 대부분 `platform` 이 들어 있고, 그 때문에 webmvc · webflux · validation · springdoc 이 함께 온다.
- **컴파일 전용**: 컴파일에만 걸고 런타임 전이는 없는 의존. 필요한 앱이 직접 한 줄 더한다 (예: `storage-s3` 의 OPAQUE 공개 URL 은 `crypto`).
- **교체 지점**: 앱이 같은 타입(또는 이름)의 빈을 만들면 모듈 기본 구현이 물러난다 (`@ConditionalOnMissingBean`). 모듈은 프레임워크 기본값을 바꾸지 않는다 — 선택은 앱 yml 이 한다.
- **프론트 짝**: 형제 레포 `react-skeleton` 의 패키지. 없으면 "없음".
- 모듈을 고르는 법과 새 프로젝트 찍기: [최소 구성 가이드](../minimal-composition.md), `scripts/new-project.sh`.
