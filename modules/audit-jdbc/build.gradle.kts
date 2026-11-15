dependencies {
    // spring-security-core: 호출자(Authentication) — 없으면 조회 경로를 등록하지 않는다
    // scheduler: 보관 기한 지난 줄 지우기의 @SkeletonScheduled 표시만 쓴다 — 없으면 표시가 무시되고 요청 구동 모드(OnRequestTask)가 부른다
    compileOnly(project(":modules:scheduler"))
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:platform"))   // OperatorAuditLog · OperatorAction 이 이 모듈의 공개 API 에 나온다
    implementation(project(":modules:persistence-jdbc"))   // SqlDialect (시각 바인딩)

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
