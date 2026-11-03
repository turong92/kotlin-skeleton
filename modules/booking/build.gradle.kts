dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (point · inquiry 와 같은 방식)
    // spring-security-core: 호출자(Authentication) — 없으면 HTTP 엔드포인트를 등록하지 않는다
    compileOnly("org.springframework.security:spring-security-core")
    // scheduler: 만료 회수의 @SkeletonScheduled 표시만 쓴다 — 없으면 표시가 무시되고 요청 구동 모드(OnRequestTask)나 잡기가 막힐 때의 그 자리 회수가 맡는다
    compileOnly(project(":modules:scheduler"))

    api(project(":modules:platform"))   // 타입(ApplicationException · TimeProvider · OnRequestTask)이 이 모듈의 공개 API 에 나온다

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
