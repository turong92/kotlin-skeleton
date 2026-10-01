// 마이그레이션 공통 (도구 무관): 설정 skeleton.migration, DB 를 지울 수 있는 설정의 프로필 가드.
// 도구별 구현은 migration-flyway (나중에 migration-liquibase) 가 이 모듈 위에 얹는다
dependencies {
    implementation("org.springframework.boot:spring-boot")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
