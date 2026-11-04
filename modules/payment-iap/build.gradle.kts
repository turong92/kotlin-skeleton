dependencies {
    // 선택 통합 - 컴파일에만 걸고 런타임 전이는 하지 않는다 (payment · coupon 과 같은 방식). 앱의 보안 설정(auth)이 호출자를 낸다
    compileOnly("org.springframework.security:spring-security-core")

    implementation(project(":modules:platform"))
    api(project(":modules:payment"))   // 계약 타입(PaymentStoreGateway · StorePurchase)이 이 모듈의 공개 API 에 나온다 - 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")   // 컨트롤러의 명세 기여(@Operation · @ApiResponseEnvelope) - payment 와 같다
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(testFixtures(project(":modules:payment")))   // 메모리 장부 · 가짜 스토어(FakeStoreGateway) · 시험 카탈로그와 플랜
    testImplementation(project(":modules:crypto"))
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
