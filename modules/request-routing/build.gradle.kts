dependencies {
    // spring-security-core: 호출자(Authentication) — 없으면 아무도 직원이 아니다(표시를 받지 않고, 창 API 경로를 등록하지 않는다)
    compileOnly("org.springframework.security:spring-security-core")
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (inquiry · alert 와 같은 방식)
    // job-queue-jdbc: 큐 차선(JobLanes 구현) — RequestRoutingJobQueueAutoConfiguration 이 클래스패스에 있을 때만 잇는다
    // spring-kafka: 후보 소비 그룹 · 표시 거르기 — RequestRoutingKafkaAutoConfiguration 이 클래스패스에 있을 때만 잇는다
    compileOnly(project(":modules:job-queue-jdbc"))
    compileOnly("org.springframework.kafka:spring-kafka")

    api(project(":modules:platform"))   // RouteBaggage · OperatorAccess · TimeProvider · 에러가 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")   // 나가는 호출 필터(ExchangeFilterFunction) — platform 의 ExternalHttpClient 가 WebClient 다
    implementation("org.springframework.boot:spring-boot-starter-validation")   // 창 열기 요청 검사(@Min · @Max · @NotBlank)
    implementation("org.springframework:spring-tx")   // 창 저장과 감사를 한 트랜잭션으로(TransactionOperations)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // 시험용 — compileOnly 와 같은 둘. 일반 test 는 선택 통합을 얹고, noOptionalTest 가 없는 쪽을 증명한다
    testImplementation(project(":modules:job-queue-jdbc"))
    testImplementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// spring-security-core · job-queue-jdbc · spring-kafka 가 클래스패스에 정말 없을 때(앱이 안 얹었을 때)를 증명하는 별도 묶음 — 일반 test 는 보안 체인 시험 때문에 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 spring-security-core · job-queue-jdbc · spring-kafka 는 따라오지 않는다
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
