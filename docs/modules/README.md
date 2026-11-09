# 모듈 색인

모듈 하나 = 의존성 한 줄. 아래 표에서 고르고, 각 문서에서 켜는 법 · 필요한 것 · 교체 지점을 본다.
모든 문서는 같은 틀이다: 의존성 한 줄 · 함께 오는 모듈 · 설정 접두사 · 기본 동작 · 부팅에 필요한 것 · 교체 지점 · 마이그레이션 · 프론트 짝 · 테스트.
`ModuleDocumentationTest`(`modules/platform`)가 문서와 코드가 어긋나면 빌드를 실패시킨다.

| 모듈 | 하는 일 | 문서 |
|---|---|---|
| `platform` | 공용 웹 기반: 표준 응답·에러 봉투, 예외 처리, trace id, 요청 로깅, CORS · rate limit 정책, 외부 HTTP 클라이언트, 시각 제공자 | [platform](platform.md) |
| `auth` | JWT 로그인 · dev-login · break-glass 와 SecurityFilterChain | [auth](auth.md) |
| `auth-session` | 리프레시 토큰(회전 · 재사용 탐지) · 세션 목록 · 로그아웃 | [auth-session](auth-session.md) |
| `auth-session-jdbc` | 세션 · 리프레시 토큰 저장소 (PostgreSQL · MySQL) | [auth-session-jdbc](auth-session-jdbc.md) |
| `account` | 계정 수명주기: 가입 · 이메일 확인 · 재설정 · 변경 · 로그인 수단 · 삭제 · 운영자 도구 | [account](account.md) |
| `account-jdbc` | 계정 · 로그인 수단 · 토큰 · 감사 저장소 (PostgreSQL · MySQL) | [account-jdbc](account-jdbc.md) |
| `auth-magic-link` | 메일 링크 로그인 (같은 계정에 얹는 로그인 수단) | [auth-magic-link](auth-magic-link.md) |
| `auth-social` | 제공자 중립 소셜 로그인 계약과 엔드포인트 | [auth-social](auth-social.md) |
| `auth-social-apple` | Apple 로그인 제공자 (iOS 앱 토큰 검증 · 탈퇴 철회) | [auth-social-apple](auth-social-apple.md) |
| `auth-social-google` | Google OAuth 제공자 | [auth-social-google](auth-social-google.md) |
| `auth-social-kakao` | Kakao OAuth 제공자 | [auth-social-kakao](auth-social-kakao.md) |
| `auth-social-naver` | Naver OAuth 제공자 | [auth-social-naver](auth-social-naver.md) |
| `auth-social-oidc` | 범용 OpenID Connect 제공자 (속성으로 여러 개 · LINE 프리셋) | [auth-social-oidc](auth-social-oidc.md) |
| `auth-social-x` | X(Twitter) OAuth 2.0 + PKCE 제공자 | [auth-social-x](auth-social-x.md) |
| `auth-web-handoff` | 앱 → 웹 한 번 쓰는 자동 로그인 코드 (메일 링크와 같은 토큰 장치, 기본 꺼짐) | [auth-web-handoff](auth-web-handoff.md) |
| `async` | 컨텍스트(trace id · MDC)를 넘기는 @Async 실행기 | [async](async.md) |
| `async-notification` | 비동기 예외를 알림으로 보낸다 | [async-notification](async-notification.md) |
| `notification` | 알림 계약 · 수신자 해석 · 인메모리 브로커 | [notification](notification.md) |
| `notification-jdbc` | 알림 인박스를 DB 테이블에 저장 | [notification-jdbc](notification-jdbc.md) |
| `notification-push` | 푸시 알림의 허리 — 알림이 받는 사람의 기기로도 간다 (기기 등록 · 종류 × 채널 끄기 · 잠금 화면 옵션) | [notification-push](notification-push.md) |
| `notification-push-jdbc` | 푸시 기기 · 종류별 푸시 끄기를 DB 에 저장하고 발송 작업을 job-queue 에 얹기 | [notification-push-jdbc](notification-push-jdbc.md) |
| `notification-broadcast` | 공지 발송 — 운영자가 전체 · 대상(역할 · 워크스페이스 · 세그먼트)에게 공지를 보낸다 (알림함 + 푸시, 예약 · 취소 · 이어 보내기 · 통계 · 광고 동의 존중) | [notification-broadcast](notification-broadcast.md) |
| `notification-broadcast-jdbc` | 공지 · 채널별 전달 숫자를 DB 에 저장하고 단계 작업을 job-queue 에 얹기 | [notification-broadcast-jdbc](notification-broadcast-jdbc.md) |
| `notification-webpush` | 웹 푸시(VAPID) 전달 수단 (자리만 — 구현은 이어서) | [notification-webpush](notification-webpush.md) |
| `notification-fcm` | 모바일 푸시(FCM) 전달 수단 (자리만 — 구현은 이어서) | [notification-fcm](notification-fcm.md) |
| `notification-legal` | 광고성 정보 게이트의 동의 어댑터(채널 무관) — legal 동의 기록으로 광고 동의 정책을 구현한다 | [notification-legal](notification-legal.md) |
| `notification-push-legal` | 광고성 푸시의 동의 어댑터 — legal 동의 기록을 읽고 동의 변경 통지 · 2년 재확인 안내 · 동의 상태 읽기를 낸다 | [notification-push-legal](notification-push-legal.md) |
| `notification-websocket` | STOMP/WebSocket 알림 전달 | [notification-websocket](notification-websocket.md) |
| `notification-sse` | SSE 알림 전달 | [notification-sse](notification-sse.md) |
| `notification-slack` | Slack 웹훅 알림 · 예외 알림 | [notification-slack](notification-slack.md) |
| `storage` | 저장소 계약 · 파일 검증 | [storage](storage.md) |
| `storage-s3` | S3 / R2 저장소 (presign · 서버 업로드) | [storage-s3](storage-s3.md) |
| `storage-image` | 업로드한 이미지의 변환본(썸네일 · 표시본)과 남에게는 원본을 주지 않는 규칙 (기본 꺼짐) | [storage-image](storage-image.md) |
| `storage-image-jdbc` | 이미지 상태 저장소 (PostgreSQL · MySQL) | [storage-image-jdbc](storage-image-jdbc.md) |
| `storage-image-imageio` | 기본 변환 엔진: 앱 안 순수 자바(ImageIO), 추가 서버 없음 | [storage-image-imageio](storage-image-imageio.md) |
| `storage-image-imgproxy` | 선택 변환 엔진: imgproxy 컨테이너 (HEIC 입력 · WebP 출력) | [storage-image-imgproxy](storage-image-imgproxy.md) |
| `storage-image-pdfbox` | PDF 첫 쪽 썸네일 (기본 꺼짐, 상한 있음) | [storage-image-pdfbox](storage-image-pdfbox.md) |
| `board` | 게시판: 글 · 댓글 트리(대댓글) · 설정으로 늘리는 반응 종류, 운영자 숨김 · 고정, 댓글 알림 | [board](board.md) |
| `board-jdbc` | 게시판 저장소 (PostgreSQL · MySQL) | [board-jdbc](board-jdbc.md) |
| `author-profile` | 작성자 프로필: 한 계정이 닉네임 여러 개를 글 · 댓글마다 골라 쓴다 (서로 이어지지 않게), 첫 프로필 넘겨받기 | [author-profile](author-profile.md) |
| `author-profile-jdbc` | 작성자 프로필 저장소 (PostgreSQL · MySQL) | [author-profile-jdbc](author-profile-jdbc.md) |
| `legal` | 약관 · 개인정보 처리방침 · 동의 기록: 판 · 시행일 · 해시 못 박기 · 가입 동의 · 철회 · 재동의 (문서는 앱이 싣는다, 예시 TEMPLATE 은 prod 에서 막는다) | [legal](legal.md) |
| `legal-jdbc` | 약관 · 동의 저장소 (PostgreSQL · MySQL, 더하기만 하는 트리거) | [legal-jdbc](legal-jdbc.md) |
| `authz` | 운영자 역할 관리 API: 역할 만들기 · 권한 고르기, 권한 카탈로그, 전권 고정 · 권한 상승 막기 · 자기 잠금 막기 (기본 꺼짐) | [authz](authz.md) |
| `authz-jdbc` | 역할 → 권한 표 저장소 (PostgreSQL · MySQL) + 첫 기동 씨앗 | [authz-jdbc](authz-jdbc.md) |
| `dashboard` | 관리자 대시보드 조립기: 다른 모듈이 기여한 타일 · 순위 · 퍼널을 모아 한 화면 분량으로, 짧은 캐시(프로젝트별) (기본 꺼짐) | [dashboard](dashboard.md) |
| `dashboard-jdbc` | 대시보드 일별 집계 · 화면 설정 저장소 (PostgreSQL · MySQL) + 일별 적재 작업을 켠다 | [dashboard-jdbc](dashboard-jdbc.md) |
| `dashboard-cloudflare` | 대시보드에 방문 · 페이지뷰 · 유입 · 경로 · 국가 · 기기 (Cloudflare Web Analytics) — 어제까지는 일별 표, 오늘은 5분 캐시, 키가 없으면 그 카드만 비어 있다 | [dashboard-cloudflare](dashboard-cloudflare.md) |
| `dashboard-ga` | 대시보드에 유입 채널 · 캠페인 · 첫 페이지 · 페이지 이동 · 새/재방문 (GA4, 어제까지 새벽 적재)와 현재 방문자(실시간, 60초 캐시) — 키가 없으면 그 카드만 비어 있다 | [dashboard-ga](dashboard-ga.md) |
| `ads-admob` | AdMob 보상형 광고 서버 측 확인(SSV) 콜백 수신: 구글 서명 확인 · 허용 광고 단위 · 같은 거래 번호는 한 번만 포인트 적립 (기본 꺼짐) | [ads-admob](ads-admob.md) |
| `point` | 포인트 원장: 적립(유효기간 선택) · 소멸 임박 순 차감 · 하루 한 번 소멸 · 운영자 조정, 보상 지급기 `point` (사용은 서버 API 뿐, 운영 경로 기본 꺼짐) | [point](point.md) |
| `point-jdbc` | 포인트 원장 저장소 (PostgreSQL · MySQL): 잔액 줄 잠금 · 지급 건 멱등 · 음수 잔액 금지 | [point-jdbc](point-jdbc.md) |
| `payment` | 결제 계약 · 제공자 라우팅 | [payment](payment.md) |
| `payment-appstore` | App Store 앱 안 결제 제공자 (애플 서명 확인 + 서버 알림 V2) | [payment-appstore](payment-appstore.md) |
| `payment-googleplay` | Google Play 앱 안 결제 제공자 (구매 확인 + 실시간 개발자 알림) | [payment-googleplay](payment-googleplay.md) |
| `payment-iap` | 앱 인앱결제 서버 HTTP (설정 · 지역 스위치 · 구매 확인) | [payment-iap](payment-iap.md) |
| `payment-jdbc` | 결제 장부 저장소 + 작업 어댑터 (PostgreSQL · MySQL) | [payment-jdbc](payment-jdbc.md) |
| `payment-toss` | Toss 결제 제공자 | [payment-toss](payment-toss.md) |
| `payment-kakaopay` | 카카오페이 직접 연동 단건 결제 제공자 (토스 계약 없이) | [payment-kakaopay](payment-kakaopay.md) |
| `payment-naverpay` | 네이버페이 직접 연동 단건 결제 제공자 (토스 계약 없이) | [payment-naverpay](payment-naverpay.md) |
| `payment-stripe` | Stripe 결제 제공자 | [payment-stripe](payment-stripe.md) |
| `payment-paddle` | Paddle 해외 결제 제공자 | [payment-paddle](payment-paddle.md) |
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
| `campaign` | 이벤트 — 출석 · 친구초대를 판정해 보상 지급 건으로 기록 (쿠폰 · 포인트를 모른다) | [campaign](campaign.md) |
| `campaign-jdbc` | 이벤트 · 출석 · 초대 저장을 DB 에 | [campaign-jdbc](campaign-jdbc.md) |
| `reward` | 보상 지급의 허리 — 이벤트가 기록한 지급 건을 일꾼이 쿠폰 · 포인트 지급기로 주고 받았어요 알림까지 | [reward](reward.md) |
| `reward-jdbc` | 지급 건 저장 · 작업을 DB 에 두고 일꾼을 job-queue 에 얹기 | [reward-jdbc](reward-jdbc.md) |
| `api-key` | API 키 — 발급(다시 인증) · 범위(표시한 경로만, 기본 거부) · 만료 · 회수 · 키별 한도 · 요청 인증 | [api-key](api-key.md) |
| `api-key-jdbc` | API 키 저장을 DB 에 두기 (해시만 저장) | [api-key-jdbc](api-key-jdbc.md) |
| `inquiry` | 문의 · 의견 받기 (질문 · 기능 제안 · 오류 신고 — 공개 POST · 접수 경보 · 운영자 목록 · 처리 표시) | [inquiry](inquiry.md) |
| `inquiry-jdbc` | 문의 저장을 DB 에 두기 | [inquiry-jdbc](inquiry-jdbc.md) |
| `auth-mfa` | 2단계 인증(TOTP) — 모든 로그인 길의 두 번째 단계 · 등록(QR) · 복구 코드 · 한도 · 재사용 방지 · 끄기(다시 인증 + 코드) | [auth-mfa](auth-mfa.md) |
| `auth-mfa-jdbc` | 2단계 저장을 DB 에 두기 (비밀은 암호화 봉투, 복구 코드는 해시) | [auth-mfa-jdbc](auth-mfa-jdbc.md) |
| `booking` | 예약(좌석 지정 · 선착순) — 자리 잡기 · 놓기 · 보류 시간 자동 반환 · 무료 확정 · 결제 확정 · 늦은 결제 자동 환불 · 배치도 스냅샷 + SSE 흐름(Redis 중계) · 이중 판매 0 | [booking](booking.md) |
| `booking-jdbc` | 예약 저장을 DB 에 두기 (자리 줄 조건부 갱신이 정본, PostgreSQL · MySQL) | [booking-jdbc](booking-jdbc.md) |
| `waiting-room` | 대기열(가상 대기실) — 로그인한 사람만 줄 서기 · 오픈 순간 무작위 섞기 · 분당 입장 수 · 서명 입장권 `requireEntry` | [waiting-room](waiting-room.md) |
| `waiting-room-jdbc` | 대기열 저장을 DB 에 두기 (번호는 방 줄 잠금 뒤에 — 1..n 빈틈 없음, PostgreSQL · MySQL) | [waiting-room-jdbc](waiting-room-jdbc.md) |
| `video` | 영상 올리기 · 재생 — 저장소로 직접 멀티파트(이어 올리기) · 정책 · 용량 · 일꾼 API(변환은 별도 일꾼) · 비공개 재생 출입증 · 삭제 · 청소 (기본 꺼짐) | [video](video.md) |
| `video-jdbc` | 영상 저장을 DB 에 두기 (표 셋, 상태 전이는 조건부 갱신, PostgreSQL · MySQL) | [video-jdbc](video-jdbc.md) |
| `video-cloudrun` | Cloud Run 작업 깨우기 — 창 안 실행 상한 · 최소 간격 · 연속 실패 경보, 기본 꺼짐 | [video-cloudrun](video-cloudrun.md) |
| `badge` | 배지 — 정의 · 수여/회수 · 내 배지 · 비로그인 공개 프로필 배지 · 보상 지급기 (포인트 · 쿠폰을 모른다) | [badge](badge.md) |
| `badge-jdbc` | 배지 저장을 DB 에 두기 (정의 · 보유 · 수여/회수 내역) | [badge-jdbc](badge-jdbc.md) |
| `workspace` | 워크스페이스(팀) — 만들기 · 역할(소유자 · 관리자 · 멤버) · slug · 멤버 관리 · 소유권 이전 · 요청 경계 `CurrentWorkspace` | [workspace](workspace.md) |
| `workspace-jdbc` | 워크스페이스 저장을 DB 에 두기 (워크스페이스 · slug 기록 · 멤버 · 초대) | [workspace-jdbc](workspace-jdbc.md) |
| `review` | 리뷰 · 평점 — 별점 1~5 · 본문 · 계정×대상 하나 · 평균/분포 · 도움돼요 · 구매 인증 · 비로그인 읽기 (상품 · 결제 · 보상을 모른다) | [review](review.md) |
| `review-jdbc` | 리뷰 저장을 DB 에 두기 (리뷰 · 대상별 집계 · 도움돼요 · 구매 신호) | [review-jdbc](review-jdbc.md) |
| `search` | DB 전문 검색 — 한국어 2글자 조각, 밀어 넣기 포트(`SearchIndexer`) · 질의 · 재색인 · 하이라이트 위치 (다른 모듈을 모른다) | [search](search.md) |
| `search-jdbc` | 검색 색인을 DB 에 (PostgreSQL tsvector · MySQL ngram FULLTEXT) · 재색인 작업 | [search-jdbc](search-jdbc.md) |
| `captcha-turnstile` | Cloudflare Turnstile 토큰 검증 | [captcha-turnstile](captcha-turnstile.md) |
| `catalog` | 상품 · 가격 카탈로그 - 월 · 연 · 단건 · 행사가 · 통화별 표를 운영자 화면에서 관리하고 결제 계약(플랜 · 단건 · 결제사 가격 id)을 구현 | [catalog](catalog.md) |
| `catalog-jdbc` | 상품 · 가격 저장을 DB 에 두기 (상품 · 이름 · 가격 · 결제사 링크) | [catalog-jdbc](catalog-jdbc.md) |
| `coupon` | 쿠폰 - 단계 목록 정의 · 쿠폰함 · 공용 코드 · 보상 지급기 · 결제 할인 수단 | [coupon](coupon.md) |
| `coupon-jdbc` | 쿠폰 저장을 DB 에 두기 (정의 · 쿠폰함 · 코드) | [coupon-jdbc](coupon-jdbc.md) |
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
