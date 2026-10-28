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
    id("org.graalvm.buildtools.native") version "0.11.3" apply false   // 선택 빌드 -Pnative 일 때만 앱에 적용된다 (아래 · docs/native-image.md)
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
            // 기본은 21. 네이티브 이미지는 GraalVM 25 컨테이너 안에서 -PjavaToolchain=25 로 빌드한다 (Dockerfile.native · docs/native-image.md) — 바이트코드 목표는 아래 JvmTarget 21 그대로
            languageVersion = JavaLanguageVersion.of(providers.gradleProperty("javaToolchain").map(String::toInt).getOrElse(21))
        }
    }

    // 다른 JDK 로 컴파일해도(-PjavaToolchain=25) 자바 쪽 바이트코드 목표는 21 로 둔다 — Kotlin 의 JVM_21 과 맞아야 한다
    if (providers.gradleProperty("javaToolchain").isPresent) tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

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
        // Testcontainers 의 Ryuk 대기 기본값은 30초인데, 바쁜 호스트(여러 세션이 같이 쓰는 맥)에서는 도커가 Ryuk 컨테이너를 올리고 포트를 여는 데만 30초가 넘게 걸린다(실측 PT30.35S).
        // 그러면 그 JVM 의 첫 DB 시험이 ExceptionInInitializerError(RyukResourceReaper), 나머지는 NoClassDefFoundError 로 줄줄이 죽는다 — docs/testing.md
        environment("TESTCONTAINERS_RYUK_CONTAINER_TIMEOUT", "120")
    }
}

// 네이티브 이미지는 **선택 빌드**다 — `-Pnative` 를 줄 때만 부트 앱에 GraalVM 플러그인을 건다. 안 주면 이 블록은 아무 일도 하지 않는다(기본 JVM 빌드는 그대로).
//   ./gradlew :apps:api:nativeCompile -Pnative -PjavaToolchain=25     (보통 scripts/native-build.sh · Dockerfile.native 가 GraalVM 25 컨테이너에서 부른다)
// 루트가 모든 서브프로젝트에 java-library 를 걸어서 플러그인이 기본으로 공유 라이브러리(--shared)를 고르므로 실행 파일로 돌려놓는다.
// 라이브러리 설정은 플러그인의 reachability metadata 저장소가 대고, 이 레포의 코드가 필요로 하는 것은 모듈이 RuntimeHintsRegistrar 로 직접 낸다 (CLAUDE.md "네이티브 리플렉션 규칙").
if (providers.gradleProperty("native").isPresent) {
    subprojects {
        pluginManager.withPlugin("org.springframework.boot") {
            pluginManager.apply("org.graalvm.buildtools.native")
            extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
                binaries.named("main") {
                    sharedLibrary.set(false)
                    imageName.set("app")
                }
                // 라이브러리별 reachability metadata 저장소(플러그인이 받아 온다)를 쓰되, 자기 jar 에 더 새 설정이 든 것은 jar 쪽을 쓴다
                (this as org.gradle.api.plugins.ExtensionAware).extensions
                    .getByType<org.graalvm.buildtools.gradle.dsl.GraalVMReachabilityMetadataRepositoryExtension>().excludedModules.addAll("org.flywaydb:flyway-core", "org.flywaydb:flyway-database-postgresql")
            }
        }
    }
}

// DB 통합 테스트를 PostgreSQL · MySQL 두 벌로 돈다. 같은 테스트 소스(src/dbTest), 묶음마다 방언 모듈 하나만 끼운다 (한 DB 로 충분한 시험은 src/postgresTest)
// (실제 앱처럼 db-* 모듈은 정확히 하나여야 하므로 한 클래스패스에 둘을 넣지 않는다)
val dbTestModules = setOf(":modules:job-queue-jdbc", ":modules:notification-jdbc", ":modules:board-jdbc", ":modules:alert-jdbc", ":modules:legal-jdbc", ":modules:authz-jdbc", ":modules:auth-session-jdbc", ":modules:account-jdbc", ":modules:author-profile-jdbc", ":modules:payment-jdbc", ":modules:dashboard-jdbc", ":modules:reward-jdbc", ":modules:campaign-jdbc")
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
// 조합을 실제로 찍어 각각 ./gradlew build 하는 `scripts/test-new-project.sh --full` 은 하루 한 번 CI 의 nightly 워크플로(.github/workflows/nightly.yml)가 돈다.
// 그래서 push 의 ci.yml 은 `-x newProjectChecks` 로 이 태스크를 건너뛴다 (같은 --quick 을 new-project.yml 이 돈다).
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
