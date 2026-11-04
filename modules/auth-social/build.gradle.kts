plugins {
    `java-test-fixtures`   // 가짜 제공자 서버(FakeIdp: JWKS · 토큰 서명 · 임의 경로 응답)를 제공자 모듈 시험이 다시 쓴다 — main 에는 없다 (src/testFixtures)
}

dependencies {
    implementation(project(":modules:platform"))
    api(project(":modules:auth"))   // 계약 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    api("org.springframework.security:spring-security-oauth2-jose")   // 그 안의 nimbus-jose-jwt — IdTokenVerifier(auth.social.idtoken)의 공개 타입(JWTClaimsSet · JWSAlgorithm)이 제공자 모듈에 나온다
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")   // ExternalHttpClient 의 Mono — JwksCache 가 JWKS 를 받는다
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testFixturesImplementation(project(":modules:platform"))   // 시험용 http 클라이언트
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testFixturesImplementation("org.springframework.security:spring-security-oauth2-jose")   // nimbus — 시험 키가 JWT 를 서명한다
}
