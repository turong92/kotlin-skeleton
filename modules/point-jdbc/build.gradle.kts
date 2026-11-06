dependencies {
    api(project(":modules:point"))   // 포트 · 모델 타입(PointStore · PointEntry)이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(project(":modules:persistence-jdbc"))   // SqlDialect (시각 바인딩 · 방언 차이)
    implementation(project(":modules:platform"))   // TimeProvider · SortableIds
    compileOnly(project(":modules:job-queue-jdbc"))   // 불명 건 조회 작업(JobQueue · JobHandler) - 클래스패스에 있을 때만 PointOperationJobsAutoConfiguration 이 선다

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // src/dbTest — postgresTest · mysqlTest 묶음이 물려받는다 (루트 build.gradle.kts dbTestModules)
    testImplementation(project(":modules:job-queue-jdbc"))   // 조회 작업 처리기 시험(Job 타입)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
