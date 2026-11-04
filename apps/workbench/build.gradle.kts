plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth-social-google"))
    implementation(project(":modules:auth-social-kakao"))
    implementation(project(":modules:auth-social-naver"))
    implementation(project(":modules:auth-social-oidc"))
    implementation(project(":modules:auth-social-x"))
    implementation(project(":modules:async-notification"))
    implementation(project(":modules:config-aws-ssm"))
    implementation(project(":modules:crypto"))
    implementation(project(":modules:event-kafka"))
    implementation(project(":modules:idempotency"))
    implementation(project(":modules:json"))
    implementation(project(":modules:notification-slack"))
    implementation(project(":modules:notification-jdbc"))
    implementation(project(":modules:notification-push-jdbc"))   // + notification-push (api) — 푸시 알림 계약 · 채널 · 기기 저장. 꺼짐이 기본이라 클래스패스에만 둔다(설정 스니펫 · 모듈 목록 시험). 전달 수단(webpush · fcm) · 견본 조립은 apps/sample
    implementation(project(":modules:notification-broadcast-jdbc"))   // + notification-broadcast (api) - 공지 발송. 꺼짐이 기본이라 클래스패스에만 둔다(설정 스니펫 시험). 견본 조립은 apps/sample
    implementation(project(":modules:notification-webpush"))   // 웹 푸시(VAPID) 전달 수단 - 꺼짐이 기본이라 클래스패스에만 둔다(설정 스니펫 시험). 견본 조립은 apps/sample
    implementation(project(":modules:notification-fcm"))   // 모바일 푸시(FCM) 전달 수단 - 꺼짐이 기본이라 클래스패스에만 둔다(설정 스니펫 시험). 견본 조립은 apps/sample
    implementation(project(":modules:notification-push-legal"))   // 광고성 푸시의 동의 어댑터 - 광고 종류를 설정하지 않으면 빈이 하나도 안 선다(꺼짐). 클래스패스에만 둔다(공존 증명). 견본 조립은 apps/sample
    implementation(project(":modules:notification-sse"))
    implementation(project(":modules:notification-websocket"))
    implementation(project(":modules:payment-stripe"))
    implementation(project(":modules:payment-paddle"))
    implementation(project(":modules:payment-toss"))
    implementation(project(":modules:payment-kakaopay"))
    implementation(project(":modules:payment-naverpay"))
    implementation(project(":modules:payment-appstore"))   // 꺼진 채 공존한다 - 흐름은 apps/sample
    implementation(project(":modules:payment-googleplay"))
    implementation(project(":modules:payment-iap"))   // 앱 인앱결제 HTTP - 기본 꺼짐(skeleton.payment-iap.enabled)
    implementation(project(":modules:payment-jdbc"))   // + payment (api) — 결제 장부(주문 · 환불 · 웹훅 수신함 · 작업)가 켜진다: /api/v1/payments. PaymentCatalog 빈이 없어 주문 만들기는 닫혀 있다(상품은 앱이 정한다 — 실제 흐름은 apps/sample 의 cheer/)
    implementation(project(":modules:persistence-jdbc"))
    implementation(project(":modules:persistence-jpa"))
    implementation(project(":modules:redis-cache"))
    implementation(project(":modules:redis-lock"))
    implementation(project(":modules:redis-rate-limit"))
    implementation(project(":modules:scheduler"))
    implementation(project(":modules:storage-s3"))
    implementation(project(":modules:storage-image"))   // 이미지 변환본 (thumb · display) - 꺼진 채 공존한다(skeleton.storage.image.enabled=false). 실제 흐름은 apps/sample
    implementation(project(":modules:storage-image-jdbc"))
    implementation(project(":modules:storage-image-imageio"))
    implementation(project(":modules:storage-image-imgproxy"))   // 선택 엔진(HEIC/AVIF 입력) - 꺼진 채 공존한다. 켜면 imgproxy 컨테이너가 필요하다
    implementation(project(":modules:storage-image-pdfbox"))     // PDF 1쪽 썸네일 엔진 - 꺼진 채 공존한다
    implementation(project(":modules:time"))
    implementation(project(":modules:job-queue-jdbc"))
    implementation(project(":modules:notification-mail"))
    implementation(project(":modules:alert-jdbc"))   // + alert (api)
    implementation(project(":modules:reward-jdbc"))   // + reward (api) — 지급 건 기록 · 일꾼. 지급기(쿠폰 · 포인트)가 없어 건은 FAILED 로 남는다. 실제 흐름은 apps/sample
    implementation(project(":modules:badge-jdbc"))   // + badge (api) — 배지 정의 · 수여/회수 · 내 배지 · 비로그인 공개 프로필 배지, 보상 지급기 `badge`. 실제 흐름은 apps/sample
    implementation(project(":modules:workspace-jdbc"))   // + workspace (api) — 워크스페이스(팀) · 역할 · slug · 멤버 관리 · 경계 `CurrentWorkspace`. 초대 · 운영자 읽기는 앱이 켠다. 실제 흐름은 apps/sample
    implementation(project(":modules:api-key-jdbc"))   // + api-key (api) — API 키 발급 · 범위 · 회수 · 요청 인증. account 위에 얹힌 모듈이라 클래스패스에만 둔다(설정 스니펫 · 모듈 목록 시험), 자동설정은 application.yml 에서 꺼 둔다. 실제 흐름은 apps/sample
    implementation(project(":modules:inquiry-jdbc"))   // + inquiry (api) — 문의 · 의견 받기(POST /api/v1/inquiries, 접수 경보 · 사람 확인 연결, 운영자 목록은 앱이 켠다). 실제 흐름은 apps/sample
    implementation(project(":modules:auth-mfa-jdbc"))   // + auth-mfa (api) — 2단계 인증(TOTP). account 위에 얹힌 모듈이라 클래스패스에만 둔다(설정 스니펫 · 모듈 목록 시험), 자동설정은 application.yml 에서 꺼 둔다
    implementation(project(":modules:booking-jdbc"))   // + booking (api) — 예약(좌석 지정 · 선착순): 자리 잡기 · 놓기 · 보류 시간 자동 반환 · 무료 확정 · 배치도 스냅샷 + SSE. 설정 스니펫 · 모듈 목록 시험을 위해 클래스패스에 둔다.
    implementation(project(":modules:waiting-room-jdbc"))   // + waiting-room (api) — 대기열(로그인한 사람만 줄 서기 · 오픈 순간 섞기 · 분당 입장 수 · 서명 입장권). 설정 스니펫 · 모듈 목록 시험을 위해 클래스패스에 둔다.
    implementation(project(":modules:video-jdbc"))   // + video (api) — 영상 올리기(저장소로 직접 멀티파트 · 이어 올리기 · 정책 · 용량 · 청소, 켜기 skeleton.video.enabled). 설정 스니펫 · 모듈 목록 시험을 위해 클래스패스에 둔다.
    implementation(project(":modules:video-cloudrun"))   // + video (api) — Cloud Run 작업 깨우기(기본 꺼짐, skeleton.video.cloud-run.enabled). 설정 스니펫 · 모듈 목록 시험을 위해 클래스패스에 둔다.
    implementation(project(":modules:coupon-jdbc"))   // + coupon (api) — 쿠폰 정의 · 쿠폰함 · 공용 코드, 보상 지급기 `coupon` · 결제 할인 수단 `coupon`. 실제 흐름은 apps/sample
    implementation(project(":modules:campaign-jdbc"))   // + campaign (api) — 이벤트(출석 · 초대) 저장. 이벤트는 운영자가 만든다 — 아무것도 심지 않으면 비어 있다. 실제 흐름은 apps/sample
    implementation(project(":modules:catalog-jdbc"))   // + catalog (api) — 상품 · 가격 표(월 · 연 · 단건 · 행사가 · 통화별)와 결제 계약 구현(`SubscriptionPlans` · `PaymentCatalog` · `ProviderPriceRefs`). 운영자가 만들기 전에는 표가 비어 살 수 있는 것이 없다. 실제 흐름은 apps/sample
    implementation(project(":modules:review-jdbc"))   // + review (api) — 리뷰 · 평점(별점 · 본문 · 도움돼요 · 구매 인증). 대상 종류는 앱이 적어야 쓸 수 있다 — 실제 흐름은 apps/sample
    implementation(project(":modules:search-jdbc"))   // + search (api) — DB 전문 검색(한국어 2글자 조각), 밀어 넣기 포트 `SearchIndexer`. 밀어 넣는 쪽(노트 · 상품 색인)은 실제 흐름을 조립하는 앱 몫
    implementation(project(":modules:captcha-turnstile"))
    implementation(project(":modules:board"))
    implementation(project(":modules:board-jdbc"))
    implementation(project(":modules:account-jdbc"))        // + account — 클래스패스에 있어 설정 스니펫이 바인딩된다. 자동설정은 application.yml 에서 꺼 둔다 (아래 참고)
    implementation(project(":modules:auth-session-jdbc"))   // + auth-session
    implementation(project(":modules:auth-magic-link"))
    implementation(project(":modules:auth-web-handoff"))   // 앱 -> 웹 자동 로그인 코드 - 기본 꺼짐
    implementation(project(":modules:author-profile-jdbc"))   // + author-profile — account 와 같이 클래스패스에만 둔다 (설정 스니펫 · 모듈 목록 시험). 자동설정은 application.yml 에서 꺼 둔다. 실제 흐름은 apps/sample
    implementation(project(":modules:author-profile"))
    implementation(project(":modules:legal-jdbc"))          // + legal — account 와 같이 클래스패스에만 둔다 (설정 스니펫 · 모듈 목록 시험). 자동설정은 application.yml 에서 꺼 둔다. 실제 흐름은 apps/sample
    implementation(project(":modules:authz-jdbc"))          // + authz — 클래스패스에만 둔다 (설정 스니펫 · 모듈 목록 시험). 표 자동설정은 application.yml 에서 꺼 둔다. 실제 흐름은 apps/sample
    implementation(project(":modules:dashboard"))           // 클래스패스에만 둔다 (설정 스니펫 · 모듈 목록 시험). 기여 빈(가입 · 활성 · 결제)은 서지만 관리 API 가 꺼져 있다. 실제 흐름은 apps/sample
    implementation(project(":modules:dashboard-jdbc"))      // + dashboard 저장소 — 일별 집계 · 화면 설정 표
    implementation(project(":modules:point-jdbc"))          // + point — 포인트 원장(적립 · FIFO 차감 · 소멸). 클래스패스에만 둔다 (설정 스니펫 · 모듈 목록 시험), 운영 API 는 꺼져 있다
    implementation(project(":modules:dashboard-cloudflare")) // + dashboard 의 Cloudflare 카드 — 키가 없어 카드는 비어 있다
    implementation(project(":modules:dashboard-ga"))        // + dashboard 의 GA 카드 · 현재 방문자 — 키가 없어 카드는 비어 있다
    implementation(project(":modules:db-postgresql"))
    implementation(project(":modules:migration-flyway"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(project(":modules:notification-slack"))
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // ModuleConfigSnippetsTest 는 docs/config/modules/*.yml 이 모듈의 @ConfigurationProperties 기본값과 같은지 본다
    systemProperty("skeleton.repoRoot", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("docs/config/modules")).optional()
}
