dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (board · account 와 같은 방식).
    // captcha-turnstile: 로그인하지 않은 호출의 사람 확인 (InquiryTurnstileAutoConfiguration 이 클래스패스에 있고 검증기 빈이 있을 때만 잇는다)
    // alert: 접수 경보 (InquiryAlertAutoConfiguration 이 클래스패스에 있고 OwnerAlerts 빈이 있을 때만 잇는다)
    // spring-security-core: 호출자(Authentication) — 없으면 엔드포인트를 등록하지 않는다
    compileOnly(project(":modules:captcha-turnstile"))
    compileOnly(project(":modules:alert"))
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:platform"))   // 타입(ApplicationException · TimeProvider · AccountErasureListener)이 이 모듈의 공개 API 에 나온다

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // 시험용 — compileOnly 와 같은 모듈 (board 와 같다). 일반 test 는 선택 통합을 얹고, noOptionalTest 가 없는 쪽을 증명한다
    testImplementation(project(":modules:captcha-turnstile"))
    testImplementation(project(":modules:alert"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// captcha-turnstile · alert 가 클래스패스에 정말 없을 때(앱이 그 모듈을 안 더했을 때)를 증명하는 별도 묶음 — 일반 test 는 연결 시험 때문에 둘을 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 captcha-turnstile · alert 는 따라오지 않는다
                implementation("org.assertj:assertj-core")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.security:spring-security-core")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
