// 스타터: 새 프로젝트가 복사해 시작하는 최소 조립. 모듈 한 줄 = 기능 하나, 모듈 내부는 건드리지 않는다 (docs/minimal-composition.md).
// 전부 얹은 데모는 apps/workbench.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:persistence-jdbc"))
    implementation(project(":modules:db-postgresql"))      // 방언은 정확히 하나 (MySQL 이면 :modules:db-mysql)
    implementation(project(":modules:migration-flyway"))   // 공통 :modules:migration 은 api 로 따라온다
    implementation(project(":modules:time"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
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
