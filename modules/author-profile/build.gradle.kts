// 선택 통합 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (board 와 같은 방식).
// idempotency: 프로필 만들기의 @IdempotentOperation 표시 (클래스패스에 없으면 JVM 이 이 애너테이션을 건너뛴다)
dependencies {
    compileOnly(project(":modules:idempotency"))
    api(project(":modules:platform"))   // 타입(AuthorDirectory · AuthorIdentities · AuthorAdoptionListener · ApplicationException)이 이 모듈의 공개 API 에 나온다

    // account 위에 얹힌 선택 모듈이다 (auth-magic-link 와 같은 방식) — 닉네임 규칙 · 꼬리표 · 한도 · 계정 저장소 타입이 공개 생성자에 나온다
    api(project(":modules:account"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")   // HttpStatus (오류 코드) · HTTP 엔드포인트
    implementation("org.springframework.boot:spring-boot-starter-security")   // Authentication (호출자) — account 와 auth-magic-link 가 같이 쓴다
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":modules:alert"))   // 경보 리스너가 감사로 치지 않는지 보는 시험 (account 의 compileOnly 와 같은 모듈)
    testImplementation(project(":modules:idempotency"))   // 같은 키 재생 시험 (compileOnly 와 같은 모듈)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// idempotency 가 클래스패스에 정말 없을 때(앱이 그 모듈을 안 더했을 때)를 증명하는 별도 묶음 — 일반 test 는 같은 키 시험 때문에 얹는다
testing {
    suites {
        register("noOptionalTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 idempotency 는 따라오지 않는다
                implementation(sourceSets["test"].output)   // 시험용 가짜 저장소 · 시험 앱 (idempotency 를 쓰지 않는 파일만 로드된다)
                implementation("org.assertj:assertj-core")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.security:spring-security-test")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noOptionalTest") }
