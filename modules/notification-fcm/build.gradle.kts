dependencies {
    api(project(":modules:notification-push"))   // PushSender 를 구현해 FCM(HTTP v1)으로 보낸다 — 계약 타입이 공개 API 에 나온다
    implementation(project(":modules:platform"))   // DeployGuard (보호 환경 기동 검사)

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")   // 2xx 응답 name 을 DEBUG 로 남긴다
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")   // 요청 · 응답 JSON (서비스 계정 서명은 JDK 암호화 — 외부 라이브러리 없음)

    testImplementation("org.assertj:assertj-core")
    testImplementation("ch.qos.logback:logback-classic")   // 로그 캡처
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
