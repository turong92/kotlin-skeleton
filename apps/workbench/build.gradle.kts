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
    implementation(project(":modules:notification-sse"))
    implementation(project(":modules:notification-websocket"))
    implementation(project(":modules:payment-stripe"))
    implementation(project(":modules:payment-paddle"))
    implementation(project(":modules:payment-toss"))
    implementation(project(":modules:payment-kakaopay"))
    implementation(project(":modules:payment-naverpay"))
    implementation(project(":modules:payment-jdbc"))   // + payment (api) — 결제 장부(주문 · 환불 · 웹훅 수신함 · 작업)가 켜진다: /api/v1/payments. PaymentCatalog 빈이 없어 주문 만들기는 닫혀 있다(상품은 앱이 정한다 — 실제 흐름은 apps/sample 의 cheer/)
    implementation(project(":modules:persistence-jdbc"))
    implementation(project(":modules:persistence-jpa"))
    implementation(project(":modules:redis-cache"))
    implementation(project(":modules:redis-lock"))
    implementation(project(":modules:redis-rate-limit"))
    implementation(project(":modules:scheduler"))
    implementation(project(":modules:storage-s3"))
    implementation(project(":modules:time"))
    implementation(project(":modules:job-queue-jdbc"))
    implementation(project(":modules:notification-mail"))
    implementation(project(":modules:alert-jdbc"))   // + alert (api)
    implementation(project(":modules:reward-jdbc"))   // + reward (api) — 지급 건 기록 · 일꾼. 지급기(쿠폰 · 포인트)가 없어 건은 FAILED 로 남는다. 실제 흐름은 apps/sample
    implementation(project(":modules:badge-jdbc"))   // + badge (api) — 배지 정의 · 수여/회수 · 내 배지 · 비로그인 공개 프로필 배지, 보상 지급기 `badge`. 실제 흐름은 apps/sample
    implementation(project(":modules:workspace-jdbc"))   // + workspace (api) — 워크스페이스(팀) · 역할 · slug · 멤버 관리 · 경계 `CurrentWorkspace`. 초대 · 운영 화면은 다음 조각. 실제 흐름은 apps/sample
    implementation(project(":modules:coupon-jdbc"))   // + coupon (api) — 쿠폰 정의 · 쿠폰함 · 공용 코드, 보상 지급기 `coupon` · 결제 할인 수단 `coupon`. 실제 흐름은 apps/sample
    implementation(project(":modules:campaign-jdbc"))   // + campaign (api) — 이벤트(출석 · 초대) 저장. 이벤트는 운영자가 만든다 — 아무것도 심지 않으면 비어 있다. 실제 흐름은 apps/sample
    implementation(project(":modules:catalog-jdbc"))   // + catalog (api) — 상품 · 가격 표(월 · 연 · 단건 · 행사가 · 통화별)와 결제 계약 구현(`SubscriptionPlans` · `PaymentCatalog` · `ProviderPriceRefs`). 운영자가 만들기 전에는 표가 비어 살 수 있는 것이 없다. 실제 흐름은 apps/sample
    implementation(project(":modules:captcha-turnstile"))
    implementation(project(":modules:board"))
    implementation(project(":modules:board-jdbc"))
    implementation(project(":modules:account-jdbc"))        // + account — 클래스패스에 있어 설정 스니펫이 바인딩된다. 자동설정은 application.yml 에서 꺼 둔다 (아래 참고)
    implementation(project(":modules:auth-session-jdbc"))   // + auth-session
    implementation(project(":modules:auth-magic-link"))
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
