dependencies {
    implementation(project(":modules:storage"))
    implementation(platform("software.amazon.awssdk:bom:2.46.8"))

    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("software.amazon.awssdk:auth")
    implementation("software.amazon.awssdk:regions")
    implementation("software.amazon.awssdk:s3")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
