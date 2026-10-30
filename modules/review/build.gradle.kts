dependencies {
    // 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (board 와 같은 방식).
    // notification: 숨김 · 답글을 작성자에게 알리는 ReviewNotifier (클래스패스에 있을 때만 등록한다)
    // idempotency: 리뷰 쓰기의 @IdempotentOperation 표시 (없으면 JVM 이 이 애너테이션을 건너뛴다)
    // spring-security-core: 호출자(Authentication) — 없으면 경로를 등록하지 않는다
    compileOnly(project(":modules:notification"))
    compileOnly(project(":modules:idempotency"))
    compileOnly("org.springframework.security:spring-security-core")

    api(project(":modules:platform"))   // 타입(ApplicationException · AuthorDirectory · TimeProvider)이 이 모듈의 공개 API 에 나온다

    implementation("org.springframework:spring-tx")   // 리뷰 쓰기 트랜잭션 합류 (DB 드라이버는 필요 없다)
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":modules:notification"))
    testImplementation(project(":modules:idempotency"))
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// notification · idempotency 가 클래스패스에 정말 없을 때를 증명하는 별도 묶음 — 일반 test 는 둘을 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 notification · idempotency 는 따라오지 않는다
                implementation("org.assertj:assertj-core")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.security:spring-security-core")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
