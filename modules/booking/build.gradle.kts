dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (point · inquiry 와 같은 방식)
    // spring-security-core: 호출자(Authentication) — 없으면 HTTP 엔드포인트를 등록하지 않는다
    compileOnly("org.springframework.security:spring-security-core")
    // scheduler: 만료 회수의 @SkeletonScheduled 표시만 쓴다 — 없으면 표시가 무시되고 요청 구동 모드(OnRequestTask)나 잡기가 막힐 때의 그 자리 회수가 맡는다
    compileOnly(project(":modules:scheduler"))

    // payment · job-queue-jdbc: 결제 확정 · 늦은 결제의 환불 작업 (BookingPayments · booking-refund) - 둘이 함께 있을 때만 자동설정이 선다
    // waiting-room: 대기실 입장권 검사 (BookingAdmission 구현) - 있을 때만
    // redis-core / spring-data-redis: 여러 대를 잇는 자리 사건 중계 (RedisBookingSeatRelay) - 있을 때만
    compileOnly(project(":modules:payment"))
    compileOnly(project(":modules:job-queue-jdbc"))
    compileOnly(project(":modules:waiting-room"))
    compileOnly(project(":modules:redis-core"))
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")
    // spring-tx: 자리 사건을 바깥 트랜잭션이 커밋된 뒤로 미룬다 (저장소를 쓰는 앱엔 늘 있다)
    compileOnly("org.springframework:spring-tx")

    api(project(":modules:platform"))   // 타입(ApplicationException · TimeProvider · OnRequestTask)이 이 모듈의 공개 API 에 나온다

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework:spring-tx")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// payment · job-queue-jdbc · waiting-room · redis-core · scheduler 가 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 얹지 않지만, 이 묶음이 「예약만」 조립(대기열 없이, 결제 없이 무료 풀만, Redis 없이)의 증거다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 payment · job-queue-jdbc · waiting-room · redis-core · scheduler 는 따라오지 않는다
                implementation("org.assertj:assertj-core")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.springframework:spring-tx")   // 저장소(booking-jdbc)가 가져오는 것
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
