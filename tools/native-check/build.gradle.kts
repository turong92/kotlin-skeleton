// 네이티브 이미지 시험용 조립 (docs/native-image.md) — 배포 대상이 아니다. 스타터 + 결제 장부 + 구독 + 작업 큐에 **가짜 결제사**(ScriptedGateway)를 얹어
// 네이티브 바이너리로 가입 · 로그인 · 갱신 · 결제 · 구독 · 작업 · 웹훅이 도는지 scripts/native-smoke.sh 가 확인한다. 새 프로젝트에는 복사되지 않는다.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:account-jdbc"))
    implementation(project(":modules:auth-session-jdbc"))
    implementation(project(":modules:legal-jdbc"))
    implementation(project(":modules:persistence-jdbc"))
    implementation(project(":modules:db-postgresql"))
    implementation(project(":modules:migration-flyway"))
    implementation(project(":modules:time"))
    implementation(project(":modules:job-queue-jdbc"))
    implementation(project(":modules:payment-jdbc"))
    implementation(project(":modules:crypto"))
    implementation(testFixtures(project(":modules:payment")))   // ScriptedGateway — 가짜 결제사. 이 앱에서만 main 클래스패스에 올린다

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
}
