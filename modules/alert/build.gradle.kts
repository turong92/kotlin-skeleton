dependencies {
    implementation(project(":modules:platform"))
    // 선택 연동 — 컴파일에만 걸고 런타임 전이는 하지 않는다. 앱이 job-queue-jdbc · notification-mail 을 가졌을 때만 각자의 AutoConfiguration 이 붙는다
    compileOnly(project(":modules:job-queue-jdbc"))
    compileOnly(project(":modules:notification-mail"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework:spring-tx")   // 커밋 뒤에 내보내기 (TransactionSynchronization) — DB 드라이버는 필요 없다
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":modules:job-queue-jdbc"))        // 선택 연동 시험용 — 부재는 noOptionalTest 가 증명한다
    testImplementation(project(":modules:notification-mail"))
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// job-queue-jdbc · notification-mail 이 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 연동 시험 때문에 둘을 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 둘은 따라오지 않는다
                implementation(project(":modules:platform"))
                implementation("org.assertj:assertj-core")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.springframework.boot:spring-boot-starter-webmvc")
                implementation("org.springframework:spring-tx")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
