dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (authz 와 같은 방식)
    // spring-security-core: 호출자(Authentication) — 없으면 HTTP 엔드포인트를 등록하지 않는다 (noOptionalTest 가 증명)
    compileOnly("org.springframework.security:spring-security-core")

    // 선택 연동 — 컴파일에만 걸고 런타임 전이는 하지 않는다. 앱이 alert 를 가졌을 때만 적재 실패를 알리는 자동 설정이 붙는다 (클래스 이름 조건)
    compileOnly(project(":modules:alert"))

    api(project(":modules:platform"))   // 기여 계약 타입(DashboardMetric · DashboardRanking …)과 OperatorAccess · ApplicationException 이 이 모듈의 공개 API 에 나온다

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// spring-security-core 가 클래스패스에 정말 없을 때(앱이 보안 없이 기여 계약만 쓸 때)를 증명하는 별도 묶음
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
