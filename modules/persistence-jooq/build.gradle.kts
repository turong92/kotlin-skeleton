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
    testImplementation(project(":modules:db-postgresql"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 생성 기준 방언: -Pskeleton.jooq.dialect=mysql 로 바꾼다 (기본 postgresql). 생성물은 하나 — 앱이 끼운 db-* 모듈과 같게 고른다
val jooqDialect = providers.gradleProperty("skeleton.jooq.dialect").getOrElse("postgresql")
require(jooqDialect in setOf("postgresql", "mysql")) { "skeleton.jooq.dialect must be postgresql or mysql: $jooqDialect" }

// 예시 DDL + 다른 모듈의 Flyway 마이그레이션(같은 방언). 모듈 마이그레이션이 DDLDatabase 로 파싱되는지를 빌드마다 증명한다
val collectModuleDdl by tasks.registering(Sync::class) { // Sync: 지운 마이그레이션이 생성 입력에 남지 않게
    from("src/main/resources/db") { include("jooq-probe-$jooqDialect.sql") }
    from("../job-queue-jdbc/src/main/resources/db/migration/$jooqDialect")
    from("../notification-jdbc/src/main/resources/db/migration/$jooqDialect")
    into(layout.buildDirectory.dir("module-ddl"))
}

jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property { key = "scripts"; value = "build/module-ddl" }
                    // 앱 레시피는 자기 마이그레이션 폴더 하나라 sort=flyway. 여기는 예시 DDL 이 섞여 semantic
                    property { key = "sort"; value = "semantic" }
                    property { key = "unqualifiedSchema"; value = "none" }
                    property { key = "defaultNameCase"; value = "lower" }
                    property { key = "parseIgnoreComments"; value = "true" }   // MySQL 마이그레이션의 [jooq ignore] 마커
                }
                forcedTypes {
                    if (jooqDialect == "postgresql") {
                        // *_at timestamptz = 일어난 시점 → jOOQ INSTANT (런타임 바인딩은 jOOQ 가 OffsetDateTime 으로)
                        forcedType {
                            name = "INSTANT"
                            includeExpression = "(?i:.*_at)"
                            includeTypes = "(?i:timestamp.*with.*time.*zone)"
                        }
                    } else {
                        forcedType {
                            userType = "java.time.Instant"
                            converter = "dev.sumin.skeleton.persistence.jooq.UtcInstantConverter"
                            includeExpression = "(?i:.*_at)"
                            includeTypes = "(?i:datetime.*|timestamp.*)"
                        }
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

sourceSets.main {
    java.srcDir("build/generated-src/jooq/main")
}

tasks.named("jooqCodegen") {
    dependsOn(collectModuleDdl)
    inputs.dir(layout.buildDirectory.dir("module-ddl"))
    inputs.property("skeleton.jooq.dialect", jooqDialect)
}
tasks.named("compileKotlin") { dependsOn("jooqCodegen") }
tasks.named("compileJava") { dependsOn("jooqCodegen") }
