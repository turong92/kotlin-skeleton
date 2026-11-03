dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (booking 과 같은 방식)
    // spring-security-core: 호출자(Authentication) — 없으면 HTTP 엔드포인트를 등록하지 않는다
    // scheduler: 올리기 청소의 @SkeletonScheduled 표시만 쓴다 — 없으면 표시가 무시되고 요청 구동 모드(OnRequestTask)가 맡는다
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly(project(":modules:scheduler"))
    // alert: 영상 경보 셋(VIDEO_QUEUE_STALE · VIDEO_WAKE_FAILED · VIDEO_FAILED_SURGE · VIDEO_COMPAT_STALE) — alert 가 있을 때만 서는 별도 자동설정
    compileOnly(project(":modules:alert"))
    // storage-image: 움직이는 GIF · WebP 표시본 변환(ANIMATED 차선) - storage-image 가 있고 둘 다 켜졌을 때만 서는 별도 자동설정. storage-image 는 video 를 모른다
    compileOnly(project(":modules:storage-image"))

    api(project(":modules:platform"))   // 타입(ApplicationException · TimeProvider · OnRequestTask)이 이 모듈의 공개 API 에 나온다
    api(project(":modules:storage"))    // PresignedStorage · StorageProperties 가 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation(project(":modules:storage-image"))   // 표시본 변환 시험용 — compileOnly 와 같은 모듈. 부재는 noOptionalTest 가 증명한다
    testImplementation(project(":modules:alert"))   // 경보 시험용 — compileOnly 와 같은 모듈. 부재는 noOptionalTest 가 증명한다
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// scheduler 가 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 얹지 않지만, 이 묶음이 「스케줄러 없는」 조립의 증거다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 scheduler 는 따라오지 않는다
                implementation("org.assertj:assertj-core")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.springframework.boot:spring-boot-starter-webmvc")   // 서블릿 앱 흉내(WebApplicationContextRunner)
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
