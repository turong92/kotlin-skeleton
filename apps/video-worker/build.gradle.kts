plugins {
    application
}

// 영상 변환 일꾼 — 서버 모듈에 의존하지 않는다. 계약은 서버의 일꾼 API JSON 한 장(/api/internal/video/*, Api.kt 의 DTO 가 그 복제)이고 저장소 키는 갖지 않는다.
// 이미지: ./gradlew :apps:video-worker:installDist 뒤 이 폴더를 컨텍스트로 docker build (Dockerfile 머리말 · docs/modules/video.md)
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.sumin.skeleton.videoworker.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx96m", "-Djdk.httpclient.allowRestrictedHeaders=host")
}

tasks.withType<Test>().configureEach {
    // ffmpeg 통합 시험은 호스트에 ffmpeg 가 있을 때만 돈다(없으면 건너뜀) — 있으면 임시 영상을 합성한다
    maxHeapSize = "256m"
    jvmArgs("-Djdk.httpclient.allowRestrictedHeaders=host")
    testLogging { events("failed", "skipped") }
}
