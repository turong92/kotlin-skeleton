dependencies {
    api(project(":modules:platform"))
    api(project(":modules:point"))   // PointWallet 이 이 모듈의 공개 API(교체 지점)에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework:spring-tx")   // DataAccessException — 적립 중 DB 오류를 503 으로
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")   // @Hidden — 구글이 부르는 주소라 공개 문서에서 뺀다
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
