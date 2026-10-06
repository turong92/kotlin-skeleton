// 샘플 앱 "Notes": 스타터(apps/api)에 모듈 몇 줄과 작은 도메인 하나(notes)만 얹은 실제 제품 모양의 예시 — docs/sample.md.
// 모듈 한 줄 = 기능 하나. 이 앱이 하는 선택(허용 파일 · 폴링 간격 …)은 application.yml 에 있고 모듈 내부는 건드리지 않는다.
plugins {
    id("org.springframework.boot")
}

dependencies {
    // 스타터와 같은 바탕
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:account-jdbc"))       // 진짜 계정: 가입 · 이메일 확인 · 재설정 · 삭제 (+ account). 시드 계정은 application-local.yml
    implementation(project(":modules:auth-session-jdbc"))  // 리프레시 토큰 · 세션 목록 (+ auth-session)
    implementation(project(":modules:auth-magic-link"))    // 메일 링크 로그인 — 같은 계정 위의 로그인 수단 하나
    implementation(project(":modules:persistence-jdbc"))
    implementation(project(":modules:db-postgresql"))      // 방언은 정확히 하나
    implementation(project(":modules:migration-flyway"))
    implementation(project(":modules:time"))
    // 이 샘플이 더한 기능 — 한 줄에 하나
    implementation(project(":modules:idempotency"))        // 만들기 두 번 눌러도 한 번 (Idempotency-Key)
    implementation(project(":modules:notification-jdbc"))  // 받은편지함 저장 (+ notification: 계약 · /api/v1/notifications)
    implementation(project(":modules:notification-sse"))   // 실시간 전달 (/api/v1/notifications/sse)
    implementation(project(":modules:storage-s3"))         // 첨부 업로드 (+ storage: /api/v1/storage)
    implementation(project(":modules:job-queue-jdbc"))     // 내보내기 같은 오래 걸리는 일 (재시도 큐)
    implementation(project(":modules:board"))              // 게시판: 글 · 대댓글 · 종류가 있는 반응 (/api/v1/boards). 댓글 알림은 위 notification 이 있어서 켜진다
    implementation(project(":modules:board-jdbc"))         // 게시판 저장소 (board 는 저장소를 모른다)
    implementation(project(":modules:auth-social-google")) // 소셜 로그인 — 꺼진 채 시작한다: 제공자마다 SKELETON_AUTH_SOCIAL_PROVIDERS_<X>_ENABLED=true + client id/secret (docs/real-provider-setup.md)
    implementation(project(":modules:auth-social-kakao"))
    implementation(project(":modules:auth-social-naver"))
    implementation(project(":modules:notification-mail")) // 계정 메일(인증 · 재설정 · 매직 링크). 로컬은 compose mail 프로필(mailpit)이 받는다
    implementation(project(":modules:alert-jdbc"))         // 주인 경보 (+ alert) — 5xx 몰림 · 죽은 작업을 Discord 웹훅으로. 웹훅 주소가 없으면 아무것도 안 보낸다

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
