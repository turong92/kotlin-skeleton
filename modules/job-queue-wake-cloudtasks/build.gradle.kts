dependencies {
    api(project(":modules:platform"))   // JobWake · TimeProvider · DeployGuard 계약이 이 모듈의 공개 API 에 나온다
    implementation(project(":modules:job-queue-jdbc"))   // 깨우기 주소의 비밀값 · 시간 한도(JobQueueProperties.Wake)를 읽는다

    implementation("org.springframework.boot:spring-boot-starter")   // 자동설정 · 로깅
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")   // 요청 · 응답 JSON (Map 으로 만든다 - DTO 없음)

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
