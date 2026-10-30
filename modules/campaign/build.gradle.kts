dependencies {
    // spring-security-core: 호출자(Authentication) — 없으면 HTTP 경로를 등록하지 않는다
    compileOnly("org.springframework.security:spring-security-core")

    // scheduler: 보류 해제 작업의 @SkeletonScheduled 표시만 쓴다 — 없으면 표시가 무시되고 앱이 `Referrals.releaseDue()` 를 부른다 (noOptionalTest 가 증명)
    compileOnly(project(":modules:scheduler"))

    api(project(":modules:reward"))   // RewardGrants · RewardIssuer 가 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게 (reward → platform)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":modules:scheduler"))   // 선택 연동 시험용 — 부재는 noOptionalTest 가 증명한다
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// scheduler 가 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 연동 시험 때문에 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 scheduler 는 따라오지 않는다
                implementation(project(":modules:reward"))
                implementation(project(":modules:platform"))
                implementation("org.assertj:assertj-core")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.springframework.boot:spring-boot-starter-webmvc")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
