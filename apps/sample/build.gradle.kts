// 샘플 앱 "Notes": 스타터(apps/api)에 모듈 몇 줄과 작은 도메인 하나(notes)만 얹은 실제 제품 모양의 예시 — docs/sample.md.
// 모듈 한 줄 = 기능 하나. 이 앱이 하는 선택(허용 파일 · 폴링 간격 …)은 application.yml 에 있고 모듈 내부는 건드리지 않는다.
plugins {
    id("org.springframework.boot")
}

dependencies {
    // 스타터와 같은 바탕
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:account-jdbc"))       // 진짜 계정: 가입 · 이메일 확인 · 재설정 · 삭제 (+ account). 시드 계정은 application-local.yml
    implementation(project(":modules:auth-session-jdbc"))  // 리프레시 토큰 · 세션 목록 (+ auth-session)
    implementation(project(":modules:auth-magic-link"))    // 메일 링크 로그인 — 같은 계정 위의 로그인 수단 하나
    implementation(project(":modules:legal-jdbc"))         // 약관 · 개인정보 처리방침 · 마케팅 동의 + 가입 동의 기록 + 재동의 (+ legal). 문서는 src/main/resources/legal/ (지금은 TEMPLATE) — docs/legal.md
    implementation(project(":modules:persistence-jdbc"))
    implementation(project(":modules:db-postgresql"))      // 방언은 정확히 하나
    implementation(project(":modules:migration-flyway"))
    implementation(project(":modules:time"))
    // 이 샘플이 더한 기능 — 한 줄에 하나
    implementation(project(":modules:idempotency"))        // 만들기 두 번 눌러도 한 번 (Idempotency-Key)
    implementation(project(":modules:notification-jdbc"))  // 받은편지함 저장 (+ notification: 계약 · /api/v1/notifications)
    implementation(project(":modules:notification-sse"))   // 실시간 전달 (/api/v1/notifications/sse)
    implementation(project(":modules:storage-s3"))         // 첨부 업로드 (+ storage: /api/v1/storage)
    implementation(project(":modules:scheduler"))          // 비회원 업로드 고아 회수의 주기 작업(@SkeletonScheduled) - storage.web.guest.cleanup
    implementation(project(":modules:job-queue-jdbc"))     // 내보내기 같은 오래 걸리는 일 (재시도 큐)
    implementation(project(":modules:board"))              // 게시판: 글 · 대댓글 · 종류가 있는 반응 (/api/v1/boards). 댓글 알림은 위 notification 이 있어서 켜진다
    implementation(project(":modules:board-jdbc"))         // 게시판 저장소 (board 는 저장소를 모른다)
    implementation(project(":modules:author-profile"))     // 작성자 프로필: 한 계정이 닉네임을 여러 개 만들어 글 · 댓글마다 골라 쓴다 (+ /api/v1/author-profiles). board 가 이것을 보고 공개 작성자 id 를 prf_… 로 낸다 — docs/modules/author-profile.md
    implementation(project(":modules:author-profile-jdbc")) // 프로필 저장소 (author-profile 은 저장소를 모른다)
    implementation(project(":modules:authz"))              // 운영자 역할 관리: 역할 만들기 · 권한 고르기 (/api/v1/admin/roles) — 켜기 skeleton.authz.admin.enabled. 문서 docs/modules/authz.md
    implementation(project(":modules:authz-jdbc"))         // 역할 → 권한 표 (authz 는 저장소를 모른다). 첫 기동에 yml 매핑을 한 번 옮긴다
    implementation(project(":modules:dashboard"))          // 관리자 대시보드: 가입 · 활성 · 순매출 · 결제 · 퍼널 카드를 모아 준다 (/api/v1/admin/dashboard) — 켜기 skeleton.dashboard.admin.enabled. 카드는 account-jdbc · auth-session-jdbc · payment-jdbc 가 기여한다. 문서 docs/modules/dashboard.md
    implementation(project(":modules:dashboard-jdbc"))     // 대시보드 일별 집계 · 화면 설정 표 + 일별 적재 작업 (dashboard 는 저장소를 모른다)
    implementation(project(":modules:dashboard-cloudflare")) // 대시보드의 방문 · 페이지뷰 · 유입 카드 (Cloudflare Web Analytics) — 키(SKELETON_DASHBOARD_CLOUDFLARE_*)가 없으면 그 카드만 비어 있다. 문서 docs/modules/dashboard-cloudflare.md
    implementation(project(":modules:dashboard-ga"))        // 대시보드의 유입 채널 · 캠페인 · 첫 페이지 · 페이지 이동 · 재방문 순위와 현재 방문자 (GA4) — 키(SKELETON_DASHBOARD_GA_*)가 없으면 그 카드만 비어 있다. 문서 docs/modules/dashboard-ga.md
    implementation(project(":modules:auth-social-google")) // 소셜 로그인 — 꺼진 채 시작한다: 제공자마다 SKELETON_AUTH_SOCIAL_PROVIDERS_<X>_ENABLED=true + client id/secret (docs/real-provider-setup.md)
    implementation(project(":modules:auth-social-kakao"))
    implementation(project(":modules:auth-social-naver"))
    implementation(project(":modules:auth-social-oidc"))   // 범용 OpenID Connect (LINE 프리셋 포함)
    implementation(project(":modules:auth-social-x"))      // X(Twitter) OAuth 2.0 + PKCE
    implementation(project(":modules:notification-mail")) // 계정 메일(인증 · 재설정 · 매직 링크). 로컬은 compose mail 프로필(mailpit)이 받는다
    implementation(project(":modules:payment-jdbc"))       // 결제 장부: 주문 · 승인 · 환불 · 웹훅 수신함 (+ payment: /api/v1/payments, 운영자 /api/v1/admin/payments). 상품은 cheer/ 의 PaymentCatalog — docs/sample.md
    implementation(project(":modules:crypto"))             // 구독의 빌링키를 암호화해 저장한다 (payment 가 TextEncryptor 를 쓴다) — 키: SKELETON_CRYPTO_KEYS_MAIN (docs/sample.md)
    implementation(project(":modules:payment-toss"))       // 토스페이먼츠 — 꺼진 채 시작한다: SKELETON_PAYMENT_TOSS_ENABLED=true + 키 둘 (docs/real-provider-setup.md). 안 켜면 결제 화면은 "지금은 결제할 수 없어요"
    implementation(project(":modules:payment-kakaopay"))   // 카카오페이 직접 연동(토스 계약 없이) — 꺼진 채 시작한다: SKELETON_PAYMENT_KAKAOPAY_ENABLED=true + SECRET_KEY · CID · RETURN_URL (docs/real-provider-setup.md). 토스와 함께 켜면 결제 화면에서 고른다
    implementation(project(":modules:payment-naverpay"))   // 네이버페이 직접 연동(토스 계약 없이) — 꺼진 채 시작한다: SKELETON_PAYMENT_NAVERPAY_ENABLED=true + MODE · CLIENT_ID · CLIENT_SECRET · CHAIN_ID · RETURN_URL (docs/real-provider-setup.md). 카카오페이 · 토스와 함께 켜면 결제 화면에서 고른다
    implementation(project(":modules:payment-paddle"))     // Paddle(해외 결제) — 꺼진 채 시작한다: SKELETON_PAYMENT_PADDLE_ENABLED=true + PADDLE_API_KEY · PADDLE_CLIENT_TOKEN · PADDLE_WEBHOOK_SECRET. 세 비밀 중 하나라도 비면 이 결제사만 없다
    implementation(project(":modules:reward-jdbc"))        // 보상 지급의 허리: 이벤트가 기록한 지급 건을 일꾼이 지급기(쿠폰 · 포인트)로 준다 (+ reward: /api/v1/admin/reward-grants) - docs/modules/reward.md
    implementation(project(":modules:catalog-jdbc"))        // 상품 · 가격 표(월 · 연 · 통화별)와 결제 계약 구현 - docs/modules/catalog.md
    implementation(project(":modules:coupon-jdbc"))        // 쿠폰: 단계 목록 정의 · 쿠폰함 · 공용 코드, 결제 할인 수단 (+ coupon: /api/v1/coupons) - docs/modules/coupon.md
    implementation(project(":modules:badge-jdbc"))         // 배지: 정의 · 수여/회수 · 내 배지 · 프로필 배지(비로그인 공개), 보상 지급기 `badge` (+ badge: /api/v1/badges). 노트 개수 업적 - notes/NoteService, docs/modules/badge.md
    implementation(project(":modules:point-jdbc"))         // 포인트: 적립 · 차감 · 소멸 (+ point: /api/v1/points) - docs/modules/point.md
    implementation(project(":modules:campaign-jdbc"))      // 이벤트: 출석 · 친구초대 (+ campaign: /api/v1/campaigns). 첫 응원 결제가 초대자 보상을 연다 - cheer/CheerOrderListener, docs/modules/campaign.md
    implementation(project(":modules:review-jdbc"))        // 리뷰 · 평점: 상품(`catalog`) 리뷰 - 산 사람만 쓴다(결제 리스너가 구매 신호), 비로그인 읽기, 사진, 운영 큐 · 답글 (+ review: /api/v1/reviews). 보상 - reviews/ReviewRewards, 구매 신호 - orders/SampleOrderListener, docs/modules/review.md
    implementation(project(":modules:search-jdbc"))        // 전문 검색 (한국어 2글자 조각, PostgreSQL tsvector) (+ search: /api/v1/search). 노트(notes/NoteService)와 catalog 상품이 색인에 들어온다 - 검색은 로그인만, 운영 경로(재색인)는 켠다. docs/modules/search.md
    implementation(project(":modules:alert-jdbc"))         // 주인 경보 (+ alert) — 5xx 몰림 · 죽은 작업을 Discord 웹훅으로. 웹훅 주소가 없으면 아무것도 안 보낸다
    implementation(project(":modules:workspace"))          // 워크스페이스(팀): 역할 · slug · 멤버 · 메일 링크 초대 · 운영자 읽기 (+ workspace: /api/v1/workspaces). 노트가 헤더 X-Workspace-Id 로 팀 노트가 된다 - 좌석 상한은 workspace/SubscriptionSeatPolicy, docs/modules/workspace.md
    implementation(project(":modules:workspace-jdbc"))     // 워크스페이스 저장소 (workspace 는 저장소를 모른다)

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 컨트롤러를 쓰는 데 필요한 것 — 모듈의 implementation 의존은 앱 컴파일에 보이지 않는다: @Valid · Authentication(호출자) · @Operation(OpenAPI)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(testFixtures(project(":modules:payment")))   // ScriptedGateway — 결제 통합 시험의 가짜 결제사 (시험 소스에만 있다)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // MigrationUpgrade 시험: MySQL 마이그레이션도 같은 방식으로 올려 본다 (앱 자체는 PostgreSQL — modules:db-mysql 은 끌어오지 않는다)
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.flywaydb:flyway-mysql")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
