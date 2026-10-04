dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // ModuleRegistrationRulesTest 가 모든 모듈의 main 소스를 훑는다 — 다른 모듈 소스가 바뀌면 이 테스트도 다시 돈다
    systemProperty("skeleton.repoRoot", rootDir.absolutePath)
    inputs.files(fileTree(rootDir) {
        include("modules/*/src/main/**/*.kt")
        exclude("**/build/**")
    })
}
