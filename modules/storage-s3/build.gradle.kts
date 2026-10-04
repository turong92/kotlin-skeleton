dependencies {
    implementation(project(":modules:crypto"))
    api(project(":modules:storage"))   // 계약 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
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
