dependencies {
    implementation(project(":modules:redis-core"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.aspectj:aspectjweaver")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.redisson:redisson:4.5.0")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
