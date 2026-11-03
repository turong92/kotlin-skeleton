dependencies {
    // 선택 통합 - 컴파일에만 건다. alert 가 있을 때만 연속 실패 경보(VIDEO_WAKE_FAILED)를 낸다
    compileOnly(project(":modules:alert"))

    api(project(":modules:video"))   // VideoWaker · VideoWakeRuns 계약이 이 모듈의 공개 API 가 된다

    implementation("org.springframework.boot:spring-boot-starter")   // 자동설정 · 로깅
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")   // 요청 · 응답 JSON (서비스 계정 서명은 JDK 암호화 - 구글 라이브러리 없음)

    testImplementation(project(":modules:alert"))
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
