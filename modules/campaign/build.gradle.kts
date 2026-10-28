dependencies {
    // spring-security-core: 호출자(Authentication) — 없으면 HTTP 경로를 등록하지 않는다
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:reward"))   // RewardGrants · RewardIssuer 가 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게 (reward → platform)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
