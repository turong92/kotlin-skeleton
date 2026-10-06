dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (board → notification · idempotency 와 같은 방식). 각각 자기 @ConditionalOnClass 자동설정이 있다.
    // notification-mail: 인증 · 재설정 메일 발송 (없으면 로컬은 링크를 로그로, stage · prod 는 DeployGuard 문제)
    // captcha-turnstile: 가입 · 재설정 · 재전송 요청의 캡차
    // alert: 로그인 시도 폭주 경보 종류
    // idempotency: 삭제 · 이메일 변경의 @IdempotentOperation
    // auth-social: 소셜 로그인 · 연결 (AccountOAuth* 가 OAuthAccountLinkRepository · OAuthAccountProvisioningPolicy 포트를 구현한다)
    // job-queue-jdbc: 삭제 유예가 끝난 계정을 지우는 일을 잡으로 (없으면 주기 실행기가 직접)
    compileOnly(project(":modules:notification-mail"))
    compileOnly(project(":modules:captcha-turnstile"))
    compileOnly(project(":modules:alert"))
    compileOnly(project(":modules:idempotency"))
    compileOnly(project(":modules:auth-social"))
    compileOnly(project(":modules:job-queue-jdbc"))

    api(project(":modules:platform"))   // ApplicationException · TimeProvider · RateLimitStore 가 이 모듈의 공개 API 에 나온다
    api(project(":modules:auth"))       // AuthAccountRepository · AuthTokenResponseFactory · SessionRevoker — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // 시험용 — compileOnly 와 같은 모듈 (board 의 notification 과 같다). 일반 test 는 선택 통합을 얹고, noOptionalTest 가 없는 쪽을 증명한다
    testImplementation(project(":modules:notification-mail"))
    testImplementation("org.springframework.boot:spring-boot-starter-mail")   // SmtpMailSender 의 발송 실패 경로(제목을 로그에 남긴다)를 실제로 태우는 시험
    testImplementation(project(":modules:captcha-turnstile"))
    testImplementation(project(":modules:alert"))
    testImplementation(project(":modules:idempotency"))
    testImplementation(project(":modules:auth-social"))
    testImplementation(project(":modules:job-queue-jdbc"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
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
