dependencies {
    api(project(":modules:async"))
    api(project(":modules:notification"))

    implementation(project(":modules:platform"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-aop")
    implementation("org.springframework:spring-context")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.assertj:assertj-core")
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
