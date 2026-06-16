dependencies {
    implementation(project(":modules:platform"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-context")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.assertj:assertj-core")
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
