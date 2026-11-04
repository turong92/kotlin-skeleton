plugins {
    `java-test-fixtures`   // 가짜 App Store 서버 · 시험용 인증서 체인을 견본 앱의 통합 시험이 다시 쓴다 - main 에는 없다 (src/testFixtures)
}

dependencies {
    implementation(project(":modules:platform"))
    api(project(":modules:payment"))   // 계약 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.security:spring-security-oauth2-jose")   // 그 안의 nimbus-jose-jwt 로 Pub/Sub push 의 OIDC 토큰(RS256)을 구글 공개키로 확인한다 — auth 모듈과 같은 의존. 서비스 계정 JWT 서명은 JDK 암호화
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testFixturesImplementation(project(":modules:platform"))   // 시험용 http 클라이언트 · TimeProvider
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testFixturesImplementation("org.springframework.security:spring-security-oauth2-jose")   // nimbus - 시험 OIDC 키가 JWT 를 서명한다
    testFixturesImplementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
