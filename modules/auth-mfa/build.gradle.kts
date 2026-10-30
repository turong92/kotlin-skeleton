dependencies {
    api(project(":modules:account"))   // Reauth · AccountCore · ChallengeStore 와 그 아래 platform · auth 의 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(project(":modules:crypto"))   // TextEncryptor — TOTP 비밀을 봉투(키 id)로 저장한다. 키가 없으면 기동 실패

    implementation("io.nayuki:qrcodegen:1.8.0")   // MIT, 의존 없음 — QR 을 서버가 만들어 SVG 경로 문자열로 내려 준다
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
