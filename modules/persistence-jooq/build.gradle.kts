// jOOQ 코드 생성: DB 없이 schema.sql(DDL) 을 파싱해서 생성 (DDLDatabase). 앱에서도 같은 구성을 복사해 쓴다 (docs/persistence-jooq.md)
plugins {
    id("org.jooq.jooq-codegen-gradle") version "3.21.7"
}

dependencies {
    implementation(project(":modules:platform"))

    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // 코드 생성 classpath (DDLDatabase 는 jooq-meta-extensions 에 있고 내부적으로 H2 를 쓴다)
    jooqCodegen("org.jooq:jooq-meta-extensions")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    // build/module-ddl 디렉토리 = 이 모듈의 예시 DDL + 다른 모듈의 Flyway 마이그레이션 (collectModuleDdl 이 모은다).
                    // 앱이 schema.sql 로 복사해 쓰는 파일들이 DDLDatabase 로 파싱되는지를 빌드마다 증명한다. 디렉토리는 sort 순으로 읽힌다
                    property { key = "scripts"; value = "build/module-ddl" }
                    // MySQL 인라인 index 절은 /* [jooq ignore start] */ … /* [jooq ignore stop] */ 로 감싸 두었고, 이 속성이 있어야 건너뛴다
                    property { key = "parseIgnoreComments"; value = "true" }
                    property { key = "sort"; value = "semantic" }
                    property { key = "unqualifiedSchema"; value = "none" }
                    property { key = "defaultNameCase"; value = "lower" }   // MySQL(리눅스)은 테이블명 대소문자 구분 → 소문자로 생성
                }
                forcedTypes {
                    // *_at DATETIME 컬럼 = 일어난 시점 → Instant (UTC). *_local 같은 벽시계 컬럼은 LocalDateTime 그대로
                    forcedType {
                        userType = "java.time.Instant"
                        converter = "dev.sumin.skeleton.persistence.jooq.UtcInstantConverter"
                        includeExpression = "(?i:.*_at)"
                        includeTypes = "(?i:datetime.*|timestamp.*)"
                    }
                }
            }
            target {
                packageName = "dev.sumin.skeleton.persistence.jooq.generated"
                directory = "build/generated-src/jooq/main"
            }
        }
    }
}

val collectModuleDdl by tasks.registering(Copy::class) {
    from("src/main/resources/db") { include("skeleton-jooq-schema.sql") }
    from("../job-queue-jdbc/src/main/resources/db/migration")
    from("../notification-jdbc/src/main/resources/db/migration")
    into(layout.buildDirectory.dir("module-ddl"))
}
tasks.named("jooqCodegen") {
    dependsOn(collectModuleDdl)
    inputs.dir(layout.buildDirectory.dir("module-ddl"))
}

sourceSets.main {
    java.srcDir("build/generated-src/jooq/main")
}

tasks.named("compileKotlin") { dependsOn("jooqCodegen") }
tasks.named("compileJava") { dependsOn("jooqCodegen") }
