import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    base   // 루트에도 check · build 라이프사이클 (아래 newProjectChecks 를 건다)
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "dev.sumin"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

configure(subprojects.filter { it.buildFile.isFile }) {
    pluginManager.apply("java-library")
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    pluginManager.apply("org.jetbrains.kotlin.plugin.spring")
    pluginManager.apply("io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

// DB 통합 테스트를 PostgreSQL · MySQL 두 벌로 돈다. 같은 테스트 소스(src/dbTest), 묶음마다 방언 모듈 하나만 끼운다
// (실제 앱처럼 db-* 모듈은 정확히 하나여야 하므로 한 클래스패스에 둘을 넣지 않는다)
val dbTestModules = setOf(":modules:job-queue-jdbc", ":modules:notification-jdbc", ":modules:board-jdbc", ":modules:alert-jdbc")
val dbSuites = mapOf(
    "postgresTest" to (":modules:db-postgresql" to "org.testcontainers:testcontainers-postgresql"),
    "mysqlTest" to (":modules:db-mysql" to "org.testcontainers:testcontainers-mysql"),
)
configure(subprojects.filter { it.path in dbTestModules }) {
    val testing = extensions.getByType<org.gradle.testing.base.TestingExtension>()
    dbSuites.forEach { (suiteName, deps) ->
        val (dialectModule, containerArtifact) = deps
        testing.suites.register(suiteName, org.gradle.api.plugins.jvm.JvmTestSuite::class.java) {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(project(dialectModule))
                implementation(containerArtifact)
            }
        }
        configurations.named("${suiteName}Implementation") {
            extendsFrom(configurations.getByName("implementation"), configurations.getByName("testImplementation"))
        }
        configurations.named("${suiteName}RuntimeOnly") {
            extendsFrom(configurations.getByName("testRuntimeOnly"))
        }
        extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
            .sourceSets.named(suiteName) { kotlin.srcDir("src/dbTest/kotlin") }
        extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()
            .named(suiteName) { resources.srcDir("src/dbTest/resources") }
        tasks.named("check") { dependsOn(suiteName) }
    }
}

// ./gradlew newMigration -Pname=add_x [-Pmodule=apps/api] [-Pvendor=postgresql]
// 지금 UTC 시각으로 V<yyyyMMddHHmmss>__<name>.sql 을 만든다. -Pvendor 를 빼면 그 모듈에 있는 vendor 폴더 전부에 같은 버전으로.
tasks.register("newMigration") {
    group = "migration"
    description = "Creates a timestamped Flyway migration (UTC yyyyMMddHHmmss)."
    doLast {
        val name = providers.gradleProperty("name").orNull ?: error("-Pname=<snake_case> is required")
        require(Regex("^[a-z0-9]+(_[a-z0-9]+)*$").matches(name)) { "-Pname must be snake_case: $name" }
        val module = providers.gradleProperty("module").orNull ?: "apps/api"
        val base = rootDir.resolve("$module/src/main/resources/db/migration")
        val vendors = providers.gradleProperty("vendor").orNull?.let { listOf(it) }
            ?: base.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted()?.ifEmpty { null }
            ?: listOf("postgresql")
        val version = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
        vendors.forEach { vendor ->
            val file = base.resolve("$vendor/V${version}__$name.sql")
            file.parentFile.mkdirs()
            check(!file.exists()) { "$file exists" }
            file.writeText("-- $name ($vendor). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)\n")
            println("created ${file.relativeTo(rootDir)}")
        }
    }
}

// scripts/new-project.sh 의 빠른 검사: 인자 검증 · 모듈 닫힘 · 파일 가지치기 · rename 잔여 검사 (Gradle 을 부르지 않는다).
// 네 조합을 실제로 찍어 각각 ./gradlew build 하는 `scripts/test-new-project.sh --full` 은 CI 워크플로(.github/workflows/new-project.yml)가 돈다.
// 찍어 낸 프로젝트에는 이 도구가 없으므로 스크립트가 있을 때만 건다.
if (file("scripts/test-new-project.sh").isFile) {
    val newProjectChecks by tasks.registering(Exec::class) {
        group = "verification"
        description = "Runs scripts/test-new-project.sh --quick."
        commandLine("bash", "scripts/test-new-project.sh", "--quick")
        inputs.files(fileTree("scripts") { exclude("**/build/**") })
        inputs.files(fileTree(rootDir) {
            include("modules/*/build.gradle.kts", "apps/*/build.gradle.kts", "settings.gradle.kts", "build.gradle.kts")
            include("docs/config/modules/*.yml", "docker-compose.yml", ".env.example")
            include("apps/api/src/**")
        })
        val marker = layout.buildDirectory.file("new-project-checks.ok")
        outputs.file(marker)
        doLast { marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") } }
    }
    tasks.named("check") { dependsOn(newProjectChecks) }
}
