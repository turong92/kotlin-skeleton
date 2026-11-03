dependencies {
    // spring-security-core: 호출자(Authentication) — 없으면 운영자 HTTP 경로를 등록하지 않는다
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:notification"))   // 알림 계약(NotificationEvent · 전달 고리 · 알림함)이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(project(":modules:platform"))   // AudienceSource · 운영자 판정 · 운영 기록 · 에러 · 응답 envelope · 시계

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
