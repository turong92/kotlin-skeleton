// 이 모듈의 main 은 런타임 부품(audit 리스너 · 변환기 · 자동 구성)뿐이다.
// jOOQ 코드 생성은 *테스트 증명용*이다: 예시 DDL(src/test/resources/db) 과 모듈 마이그레이션을 DB 없이 DDLDatabase 로 파싱해
// test 소스 세트로 생성한다. 앱에서도 같은 구성을 복사해 쓴다 (docs/persistence-jooq.md)
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
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")   // 방언 모듈(db-postgresql)이 아니라 드라이버만: 이 모듈은 다른 모듈에 기대지 않는다
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 생성 기준 방언: -Pskeleton.jooq.dialect=mysql 로 바꾼다 (기본 postgresql). 생성물은 하나 — 앱이 끼운 db-* 모듈과 같게 고른다
val jooqDialect = providers.gradleProperty("skeleton.jooq.dialect").getOrElse("postgresql")
require(jooqDialect in setOf("postgresql", "mysql")) { "skeleton.jooq.dialect must be postgresql or mysql: $jooqDialect" }

// 예시 DDL + 형제 모듈의 Flyway 마이그레이션(같은 방언). 모듈 마이그레이션이 DDLDatabase 로 파싱되는지를 빌드마다 증명한다.
// 형제 모듈이 없는 프로젝트(new-project.sh 가 가지친 경우)에서는 그 폴더가 없을 뿐이라 Sync 가 건너뛴다.
// 테스트 전용 입력이다 — main 과 jar 는 이 폴더들을 읽지 않는다.
val siblingMigrations = mapOf("job-queue-jdbc" to "jobs", "notification-jdbc" to "notification_inbox", "account-jdbc" to "accounts", "auth-session-jdbc" to "auth_sessions")   // 테이블 이름 (접두사 없음 — rename 이 바꿀 것이 없다)
val presentSiblings = siblingMigrations.filterKeys { file("../$it/src/main/resources/db/migration/$jooqDialect").isDirectory }
val collectModuleDdl by tasks.registering(Sync::class) { // Sync: 지운 마이그레이션이 생성 입력에 남지 않게
    from("src/test/resources/db") { include("jooq-probe-$jooqDialect.sql") }
    siblingMigrations.keys.forEach { from("../$it/src/main/resources/db/migration/$jooqDialect") }
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
                directory = "build/generated-src/jooq/test"
            }
        }
    }
}

sourceSets.test {
    java.srcDir("build/generated-src/jooq/test")
}
// jOOQ 플러그인은 생성 폴더를 main 에 자동으로 더한다 — 생성물은 test 에만 둔다
afterEvaluate {
    tasks.named("jooqCodegen").get()   // 플러그인은 태스크가 만들어질 때 main 에 폴더를 더한다 — 먼저 만들고 뺀다
    sourceSets.main { java.setSrcDirs(java.srcDirs.filterNot { it.path.contains("generated-src/jooq") }) }
}

tasks.test {
    // 형제 모듈 마이그레이션이 있었다면 그 테이블이 실제로 생성됐는지 확인한다 (입력이 조용히 비는 것을 막는다)
    systemProperty("skeleton.jooq.expectedModuleTables", presentSiblings.values.joinToString(","))
}

tasks.named("jooqCodegen") {
    dependsOn(collectModuleDdl)
    inputs.dir(layout.buildDirectory.dir("module-ddl"))
    inputs.property("skeleton.jooq.dialect", jooqDialect)
}
tasks.named("compileTestKotlin") { dependsOn("jooqCodegen") }
tasks.named("compileTestJava") { dependsOn("jooqCodegen") }
