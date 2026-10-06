dependencies {
    api(project(":modules:legal"))   // 포트 · 모델 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(project(":modules:persistence-jdbc"))   // SqlDialect (시각 바인딩 · 방언 차이)

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // src/dbTest — postgresTest · mysqlTest 묶음이 물려받는다 (루트 build.gradle.kts dbTestModules)
    testImplementation(project(":modules:platform"))
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
