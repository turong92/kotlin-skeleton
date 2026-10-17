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
    implementation(project(":modules:alert-jdbc"))         // 주인 경보 (+ alert) — 5xx 몰림 · 죽은 작업을 Discord 웹훅으로. 웹훅 주소가 없으면 아무것도 안 보낸다

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
