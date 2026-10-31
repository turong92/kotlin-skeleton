dependencies {
    api(project(":modules:notification-push"))   // PushSender 를 구현해 웹 푸시(VAPID)로 보낸다 — 계약 타입이 공개 API 에 나온다
    implementation(project(":modules:platform"))   // DeployGuard (보호 환경 기동 검사)

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")   // 푸시 본문 JSON (암호화 · VAPID 서명은 JDK 암호화 — 외부 라이브러리 없음)

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
