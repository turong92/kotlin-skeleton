// 마이그레이션 Flyway 구현: 옵트인 로컬 clean 전략, V<UTC 14자리> 이름 규칙 검사. 가드 · 설정은 공통 migration 모듈. Flyway 설정값은 바꾸지 않는다 (앱의 선택)
dependencies {
    api(project(":modules:migration"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // RepositoryMigrationsTest 가 레포 전체의 db/migration 을 훑는다
    systemProperty("skeleton.repoRoot", rootDir.absolutePath)
    inputs.files(fileTree(rootDir) { include("**/src/*/resources/db/migration/**"); exclude("**/build/**") })
}
