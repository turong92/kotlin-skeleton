dependencies {
    // spring-security-core: 호출자(Authentication) — 없으면 아무도 직원이 아니다(표시를 받지 않고, 창 API 경로를 등록하지 않는다)
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:platform"))   // RouteBaggage · OperatorAccess · TimeProvider · 에러가 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")   // 나가는 호출 필터(ExchangeFilterFunction) — platform 의 ExternalHttpClient 가 WebClient 다
    implementation("org.springframework.boot:spring-boot-starter-validation")   // 창 열기 요청 검사(@Min · @Max · @NotBlank)
    implementation("org.springframework:spring-tx")   // 창 저장과 감사를 한 트랜잭션으로(TransactionOperations)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// spring-security-core 가 클래스패스에 정말 없을 때(앱이 시큐리티를 안 얹었을 때)를 증명하는 별도 묶음 — 일반 test 는 보안 체인 시험 때문에 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 spring-security-core 는 따라오지 않는다
                implementation("org.assertj:assertj-core")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
