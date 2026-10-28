plugins {
    `java-test-fixtures`   // 시험 대역(ScriptedGateway · 메모리 저장소)을 payment-jdbc · 샘플 앱 시험이 다시 쓴다 — main 에는 없다 (src/testFixtures)
}

dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (board → notification 과 같은 방식). 각각 자기 @ConditionalOnClass 자동설정이 있다.
    // alert: "확인 필요" 주인 경보 (PaymentAlertAutoConfiguration 이 클래스패스에 있을 때만 등록한다)
    // spring-security-core: PaymentOperatorPolicy 의 Authentication — 운영자 경로(B7)가 쓴다
    compileOnly(project(":modules:alert"))
    compileOnly("org.springframework.security:spring-security-core")

    implementation(project(":modules:platform"))
    implementation(project(":modules:crypto"))   // 구독의 빌링키를 암호화해 저장한다(TextEncryptor — 구독을 켠 앱이 암호 키를 낸다)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")   // 웹훅 수신 경로(컨트롤러) — 서블릿 웹 앱일 때만 등록한다 (board 와 같은 방식)
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")   // 컨트롤러의 명세 기여(@Operation · @ApiResponseEnvelope) — board 와 같다
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testFixturesApi(project(":modules:crypto"))   // 구독의 빌링키를 암호화하는 TextEncryptor — 시험 조립(PaymentLedgerKit)이 대역을 낸다
    testFixturesImplementation(project(":modules:platform"))   // TimeProvider · ExternalHttp 예외 — 대역이 쓴다
    testFixturesImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testFixturesApi("org.jetbrains.kotlin:kotlin-test-junit5")   // PaymentLedgerStoreContract(저장소가 지킬 약속의 시험)를 다른 모듈이 상속한다

    testImplementation(testFixtures(project(":modules:platform")))   // RegexBlowupGuard — 웹훅 경로 마스킹이 제곱으로 느려지지 않는지
    testImplementation(project(":modules:alert"))   // 경보 연동 시험용 — compileOnly 와 같은 모듈. 부재는 noOptionalTest 가 증명한다
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")   // 웹훅 경로 시험 (MockMvc)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// alert 가 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 경보 시험 때문에 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 alert 는 따라오지 않는다
                implementation(testFixtures(project()))   // 메모리 저장소 · ScriptedGateway
                implementation("org.assertj:assertj-core")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
