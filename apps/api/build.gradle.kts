// 스타터: 새 프로젝트가 복사해 시작하는 최소 조립. 모듈 한 줄 = 기능 하나, 모듈 내부는 건드리지 않는다 (docs/minimal-composition.md).
// 전부 얹은 데모는 apps/workbench.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:account-jdbc"))       // 계정 (+ account): 가입 · 이메일 확인 · 재설정 · 삭제 — auth 가 진짜 계정으로 로그인한다
    implementation(project(":modules:auth-session-jdbc"))  // 리프레시 토큰 · 세션 목록 (+ auth-session). 메일 · 매직 링크는 docs/accounts.md
    implementation(project(":modules:legal-jdbc"))         // 약관 · 개인정보 처리방침 + 가입 동의 기록 + 재동의 (+ legal). 문서는 모듈의 TEMPLATE — prod 로 뜨기 전에 자기 문서로 바꾼다 (docs/legal.md)
    implementation(project(":modules:persistence-jdbc"))
    implementation(project(":modules:db-postgresql"))      // 방언은 정확히 하나 (MySQL 이면 :modules:db-mysql)
    implementation(project(":modules:migration-flyway"))   // 공통 :modules:migration 은 api 로 따라온다
    implementation(project(":modules:time"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 컨트롤러를 쓰는 데 필요한 것 — 모듈의 implementation 의존은 앱 컴파일에 보이지 않는다: @Valid · Authentication(호출자) · @Operation(OpenAPI)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
