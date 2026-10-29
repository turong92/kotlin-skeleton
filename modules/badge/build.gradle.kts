dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (reward · board 와 같은 방식).
    // notification: 받았어요 알림 (BadgeNotificationAutoConfiguration 이 클래스패스에 있을 때만 등록한다)
    // spring-security-core: 사용자 · 운영자 경로의 호출자(Authentication) — 없으면 경로를 등록하지 않는다
    // reward · coupon · point 는 걸지 않는다 — 배지는 그것들을 모른다. 보상 지급기(RewardIssuer)는 platform 의 계약만 구현한다
    compileOnly(project(":modules:notification"))
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:platform"))   // RewardIssuer · AccountLabels 같은 계약 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework:spring-tx")   // 커밋 뒤에 알림 보내기 (TransactionSynchronization) — DB 드라이버는 필요 없다
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":modules:notification"))   // 알림 시험 (compileOnly 와 같은 모듈 — reward 와 같다)
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// notification 이 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 알림 시험 때문에 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 notification 은 따라오지 않는다
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
