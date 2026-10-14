dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:persistence-jdbc"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")   // 요청 구동 모드의 깨우기 주소 (JobWakeController) — 서블릿 웹 앱일 때만 쓰인다
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // src/dbTest — postgresTest · mysqlTest 묶음이 물려받는다 (루트 build.gradle.kts dbTestModules)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
