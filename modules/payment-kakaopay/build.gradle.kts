dependencies {
    implementation(project(":modules:platform"))
    api(project(":modules:payment"))   // 계약 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(testFixtures(project(":modules:payment")))   // PaymentLedgerKit(메모리 장부) - 진짜 카카오페이 모듈을 장부에 꽂아 돈 흐름을 시험한다
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
