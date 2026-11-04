dependencies {
    implementation(project(":modules:platform"))
    api(project(":modules:payment"))   // 계약 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.security:spring-security-oauth2-jose")   // 그 안의 nimbus-jose-jwt 로 JWS(ES256)를 만들고 확인한다 — auth 모듈과 같은 의존. 애플 Java 라이브러리는 쓰지 않는다(OkHttp · 옛 Jackson · BouncyCastle 을 끌고 온다)
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.84")   // 시험용 인증서 체인(루트 · 중간 · 잎)을 만드는 데만 — 제품 코드에는 없다
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
