dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (notification-push 와 같은 방식)
    // spring-security-core: 호출자(Authentication) — 없으면 GET /api/v1/push/advertising 을 등록하지 않는다
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:notification-push"))   // 푸시 코어(재확인 표시 저장소 PushAdvertisingNoticeStore · 광고 게이트) — 의존성 한 줄로 충분하게
    api(project(":modules:notification-legal"))   // 채널 무관 동의 정책(AdvertisingConsentPolicy 를 legal 로 구현) - 안내 · 상태 읽기가 같은 계산을 쓴다
    api(project(":modules:legal"))   // 동의 증거는 legal 의 일이다 — 어댑터가 legal 타입을 공개 API 에 낸다
    implementation(project(":modules:platform"))   // TimeProvider · 응답 envelope · 프로젝트 문맥

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
