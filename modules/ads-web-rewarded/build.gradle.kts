dependencies {
    api(project(":modules:platform"))   // RewardIssuer · TimeProvider · DayWindow 가 이 모듈의 공개 API 에 나온다

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")   // purpose 모양 검사
    implementation("org.springframework.security:spring-security-core")   // 호출자 = Authentication.name — 이 모듈은 로그인 호출자가 전부라 선택 의존이 아니다
    implementation("org.springframework:spring-tx")   // DataAccessException — 보상 중 DB 오류를 503 으로
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
