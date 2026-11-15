dependencies {
    api(project(":modules:notification"))   // 광고 게이트의 계약(AdvertisingConsentPolicy)을 구현한다 - 채널(푸시 · 메일 · 슬랙)과 무관
    api(project(":modules:legal"))   // 동의 증거는 legal 의 일이다 — 어댑터가 legal 타입을 공개 API 에 낸다

    implementation(project(":modules:platform"))
    // 법정 통지를 메일로도 보내는 선택 연동 - 컴파일에만 걸고 런타임 전이는 하지 않는다. 부재는 noMailTest 가 증명한다
    compileOnly(project(":modules:notification-mail"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation(project(":modules:notification-mail"))   // 통지 메일 시험용 - 부재는 noMailTest 가 증명한다
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// notification-mail 이 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 통지 메일 시험 때문에 메일 모듈을 얹는다
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
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noMailTest") }
