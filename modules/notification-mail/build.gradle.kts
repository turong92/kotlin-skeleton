dependencies {
    implementation(project(":modules:platform"))   // 프로젝트 모드의 발신자(from)가 프로젝트마다 다르다 (ProjectContext)
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
