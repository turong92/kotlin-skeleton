dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (coupon · payment 와 같은 방식).
    // spring-security-core: 공개 읽기 · 운영자 경로의 호출자(Authentication) — 없으면 경로를 등록하지 않는다
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:payment"))   // SubscriptionPlans · PaymentCatalog · ProviderPriceRefs 같은 결제 계약 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(project(":modules:platform"))   // TimeProvider · OperatorAccess · SortableIds

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(testFixtures(project(":modules:payment")))   // 메모리 장부 · 가짜 결제사 - 결제 조립에서 카탈로그 빈이 서는지 (payment-jdbc 와 같은 방식)
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
