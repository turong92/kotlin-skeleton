dependencies {
    api(project(":modules:storage-image"))   // AnimatedDisplayConverter · AnimatedDisplays 가 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(project(":modules:platform"))   // UploadImages · UploadImageState (상태 줄 · 변환본 이름 계약)
    implementation(project(":modules:job-queue-jdbc"))   // 변환이 얹히는 큐 (JobQueue · JobHandler) — 별도 일꾼도 자체 폴링도 만들지 않는다

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
