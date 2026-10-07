dependencies {
    api(project(":modules:platform"))   // 타입(AuthorDirectory · AuthorIdentities · AuthorAdoptionListener · ApplicationException)이 이 모듈의 공개 API 에 나온다

    // account 위에 얹힌 선택 모듈이다 (auth-magic-link 와 같은 방식) — 닉네임 규칙 · 꼬리표 · 한도 · 계정 저장소 타입이 공개 생성자에 나온다
    api(project(":modules:account"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")   // HttpStatus (오류 코드) · HTTP 엔드포인트
    implementation("org.springframework.boot:spring-boot-starter-security")   // Authentication (호출자) — account 와 auth-magic-link 가 같이 쓴다
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
