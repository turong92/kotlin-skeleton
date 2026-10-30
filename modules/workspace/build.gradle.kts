dependencies {
    // spring-security-core: 호출자(Authentication) — 없으면 사용자 경로 · CurrentWorkspace 를 등록하지 않는다 (badge 와 같다)
    compileOnly("org.springframework.security:spring-security-core")
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (account 와 같은 방식). 자기 @ConditionalOnClass 자동설정이 있다.
    // notification-mail: 초대 메일 발송 (없으면 로컬은 링크를 로그로, stage · prod 는 DeployGuard 문제)
    compileOnly(project(":modules:notification-mail"))

    api(project(":modules:platform"))   // AccountLabels · AccountFacts · AccountDeletionCheck · AccountErasureListener 같은 계약 타입이 이 모듈의 공개 API 에 나온다

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // 시험용 — compileOnly 와 같은 모듈. 일반 test 는 선택 통합을 얹고, noOptionalTest 가 없는 쪽을 증명한다
    testImplementation(project(":modules:notification-mail"))
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 선택 통합이 클래스패스에 정말 없을 때를 증명하는 별도 묶음
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 모듈은 따라오지 않는다
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
