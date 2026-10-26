dependencies {
    api(project(":modules:dashboard"))   // 기여 계약 타입(DashboardMetric · DashboardRanking · DashboardIngestor …)이 이 모듈의 공개 API 에 나온다
    implementation(project(":modules:platform"))   // ExternalHttpClient

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
