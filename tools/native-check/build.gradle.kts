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
    // --full 시험(scripts/native-test.sh --full)이 쓰는 모듈 — 메일 · 파일 저장 · 소셜 로그인 · 토스 · Paddle · 실시간 알림. 꺼진 채 시작하는 것은 application.yml 이 시험용 값으로 켠다 (빌드 때 굳는다)
    implementation(project(":modules:notification-mail"))
    implementation(project(":modules:storage-s3"))
    implementation(project(":modules:notification-jdbc"))
    implementation(project(":modules:notification-sse"))
    implementation(project(":modules:auth-social-google"))
    implementation(project(":modules:auth-social-kakao"))
    implementation(project(":modules:auth-social-naver"))
    implementation(project(":modules:auth-social-oidc"))
    implementation(project(":modules:auth-social-x"))
    implementation(project(":modules:payment-toss"))
    implementation(project(":modules:payment-kakaopay"))
    implementation(project(":modules:payment-paddle"))
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
