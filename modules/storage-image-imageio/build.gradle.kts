dependencies {
    api(project(":modules:storage-image"))   // ImageRenderer 포트가 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.12.0")   // JDK 가 못 읽는 JPEG(CMYK 등)
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")   // WebP 읽기 (쓰기는 없다)
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation(project(":modules:platform"))   // TimeProvider (시험이 처리기를 조립한다)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
