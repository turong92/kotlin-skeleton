dependencies {
    // spring-security-core: 호출자(Authentication) — 없으면 사용자 경로 · CurrentWorkspace 를 등록하지 않는다 (badge 와 같다)
    // 초대 메일(notification-mail)은 K3 에서 compileOnly 로 더한다
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:platform"))   // AccountLabels · AccountDeletionCheck · AccountErasureListener 같은 계약 타입이 이 모듈의 공개 API 에 나온다

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
