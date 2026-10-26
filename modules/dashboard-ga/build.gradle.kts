dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다. spring-security-core 가 없는 앱은 실시간 컨트롤러만 등록되지 않는다
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:dashboard"))   // 기여 계약 타입(DashboardRanking · DashboardIngestor …)이 이 모듈의 공개 API 에 나온다
    implementation(project(":modules:platform"))   // ExternalHttpClient · OperatorAccess

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
