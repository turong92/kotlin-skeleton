dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (reward → notification 과 같은 방식).
    // auth: 세션 사건(SessionEventListener) · 호출자의 세션 id(CurrentPrincipal.sessionId) — 없으면 세션 연동만 빠진다 (src/noOptionalTest 가 증명)
    // spring-security-core: 호출자(Authentication) — 없으면 HTTP 경로를 등록하지 않는다
    compileOnly(project(":modules:auth"))
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:notification"))   // 알림 계약(NotificationEvent · 구독 등록)이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(project(":modules:platform"))   // TimeProvider · 에러 · 응답 envelope · 프로젝트 문맥

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":modules:auth"))   // 세션 사건 시험 (compileOnly 와 같은 모듈)
    testImplementation(project(":modules:auth-session"))   // 로그아웃 -> 기기 줄 사라짐 시험 (진짜 SessionService 사건 경로)
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.security:spring-security-test")   // 인증된 호출자를 보안 필터 체인(auth)에 싣는다
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// auth 가 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 세션 시험 때문에 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 auth 는 따라오지 않는다
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
