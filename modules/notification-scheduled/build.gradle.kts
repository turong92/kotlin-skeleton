dependencies {
    api(project(":modules:notification"))   // 알림 계약(NotificationPublisher · AdvertisingGate)이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(project(":modules:platform"))   // TimeProvider · AccountErasureListener
    // scheduler: 예약 시각이 된 푸시를 훑는 @SkeletonScheduled 표시만 쓴다 — 없으면 표시가 무시되고 요청 구동 모드(OnRequestTask)나 앱이 ScheduledPushSweep 을 부른다
    compileOnly(project(":modules:scheduler"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework:spring-tx")   // ScheduledPushWakeTest 가 호출자의 트랜잭션을 흉내 낸다
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
