dependencies {
    implementation(project(":modules:platform"))   // 받은편지함 HTTP 엔드포인트의 envelope · 에러 · 페이지 (서블릿 앱에서만 등록된다)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework:spring-tx")   // 커밋 뒤 전달 (클래스가 없는 앱을 위해 AfterCommit 은 있는지 먼저 본다)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    compileOnly("org.springframework.security:spring-security-core")   // 호출자(Authentication) — 없으면 엔드포인트를 등록하지 않는다
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // 광고 종류의 메일을 광고 게이트에 태우는 선택 연동 - 컴파일에만 걸고 런타임 전이는 하지 않는다(메일을 안 쓰는 앱이 메일 모듈을 끌고 오지 않는다). 부재는 noMailTest 가 증명한다
    compileOnly(project(":modules:notification-mail"))

    testImplementation(project(":modules:notification-mail"))   // 광고 메일 시험용 - 부재는 noMailTest 가 증명한다
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework:spring-tx")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// notification-mail 이 클래스패스에 정말 없을 때(앱이 :modules:notification-mail 을 안 더했을 때)를 증명하는 별도 묶음 — 일반 test 는 광고 메일 시험 때문에 메일 모듈을 얹는다
testing {
    suites {
        register("noMailTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 notification-mail 은 따라오지 않는다
                implementation(project(":modules:platform"))
                implementation("org.assertj:assertj-core")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-starter")
                implementation("org.springframework:spring-tx")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noMailTest") }
