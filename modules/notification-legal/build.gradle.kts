dependencies {
    api(project(":modules:notification"))   // 광고 게이트의 계약(AdvertisingConsentPolicy)을 구현한다 - 채널(푸시 · 메일 · 슬랙)과 무관
    api(project(":modules:legal"))   // 동의 증거는 legal 의 일이다 — 어댑터가 legal 타입을 공개 API 에 낸다

    implementation(project(":modules:platform"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
