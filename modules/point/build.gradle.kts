dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (dashboard 와 같은 방식)
    // spring-security-core: 호출자(Authentication) — 없으면 HTTP 엔드포인트를 등록하지 않는다 (noOptionalTest 가 증명)
    compileOnly("org.springframework.security:spring-security-core")
    // scheduler: 소멸 작업의 @SkeletonScheduled 표시만 쓴다 — 없으면 표시가 무시되고 `PointWallet.expireDue` 를 앱이 부른다 (소멸 안 한 묶음은 쓰는 쪽이 못 쓰게 막는다)
    compileOnly(project(":modules:scheduler"))

    api(project(":modules:platform"))   // RewardIssuer · OperatorAccess · AccountErasureListener · TimeProvider 가 이 모듈의 공개 API 에 나온다

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework:spring-tx")   // 조정과 운영 기록을 한 트랜잭션으로 (TransactionTemplate)
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
