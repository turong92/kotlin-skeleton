dependencies {
    implementation(project(":modules:platform"))   // 업로드 HTTP 엔드포인트의 envelope · 에러 (서블릿 앱 + 호출자가 있을 때만 등록된다)

    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    compileOnly("io.projectreactor:reactor-core")   // ExternalHttpClient 의 Mono - CDN 비우기(Cloudflare)는 outbound HTTP 가 있는 앱에서만 켠다
    compileOnly(project(":modules:captcha-turnstile"))   // 비회원 업로드 캡차 - 없으면 건너뛴다 (board 와 같다)
    compileOnly("org.springframework.security:spring-security-core")   // 호출자(Authentication) — 없으면 엔드포인트를 등록하지 않는다
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("io.projectreactor:reactor-core")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
