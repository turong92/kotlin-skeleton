package dev.sumin.skeleton.common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 테스트 컨테이너는 컨텍스트마다 띄우지 않는다. 컨텍스트가 컨테이너 `@Bean`(`@ServiceConnection`)을 가지면 서로 다른 스프링 컨텍스트마다
 * 컨테이너가 하나씩 뜨고, 컨텍스트 캐시가 JVM 이 끝날 때까지 그 컨텍스트를 붙들어 컨테이너가 쌓인다 (찍은 프로젝트에서 postgres:18 32 개 · 스왑 95%).
 * 정본은 "JVM 하나에 컨테이너 하나(static) + 컨텍스트마다 새 데이터베이스 + `JdbcConnectionDetails` 빈" 이다 — apps/api 의 TestcontainersConfiguration.
 *
 * 앱 · 모듈의 모든 테스트 소스 세트(src/test · dbTest · postgresTest · mysqlTest …)를 텍스트로 훑는다.
 */
class TestContainerRulesTest {
    private val repoRoot: Path =
        Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })

    @Test
    fun `no test source declares a container bean or a service connection`() {
        val violations = testSources(repoRoot).flatMap { file ->
            containerBeanViolations(file.readText()).map { "${repoRoot.relativize(file)}:${it.first} ${it.second}" }
        }
        assertEquals(
            emptyList(),
            violations,
            "컨테이너는 JVM 당 하나(static)로 띄우고 컨텍스트마다 새 데이터베이스를 준다 — apps/api 의 TestcontainersConfiguration 을 따른다 (docs/testing.md)",
        )
    }

    @Test
    fun `the rule recognises the bean shapes it exists to stop`() {
        val serviceConnection = """
            @TestConfiguration(proxyBeanMethods = false)
            class TestcontainersConfiguration {
                @Bean
                @ServiceConnection
                fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            }
        """.trimIndent()
        val plainBean = """
            @Bean
            fun redis(): GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7"))
        """.trimIndent()
        val oneLine = "@Bean fun db(): MySQLContainer = MySQLContainer(DockerImageName.parse(\"mysql:8.4\"))"
        val allowed = """
            @Bean
            fun jdbcConnectionDetails(): JdbcConnectionDetails = SharedPostgres.newDatabase()
            // @ServiceConnection 은 쓰지 않는다 — 주석은 건너뛴다
            private val container: PostgreSQLContainer by lazy { PostgreSQLContainer(DockerImageName.parse("postgres:18")).also { it.start() } }
        """.trimIndent()

        assertTrue(containerBeanViolations(serviceConnection).isNotEmpty(), "@ServiceConnection")
        assertTrue(containerBeanViolations(plainBean).isNotEmpty(), "@Bean returning a *Container")
        assertTrue(containerBeanViolations(oneLine).isNotEmpty(), "one-line @Bean returning a *Container")
        assertEquals(emptyList(), containerBeanViolations(allowed))
    }

    @Test
    fun `the scan reaches every test source set of the apps and modules`() {
        val names = testSources(repoRoot).map { repoRoot.relativize(it).toString() }
        assertTrue(names.any { it.startsWith("apps/api/src/test/") && it.endsWith("TestcontainersConfiguration.kt") }, "apps/api test sources")
        assertTrue(names.any { it.startsWith("modules/") && "/src/test/" in it }, "module src/test")
    }

    private fun testSources(root: Path): List<Path> =
        listOf("apps", "modules").map { root.resolve(it) }.filter { Files.isDirectory(it) }.flatMap { top ->
            Files.list(top).use { children ->
                children.toList().flatMap { project ->
                    val src = project.resolve("src")
                    if (!Files.isDirectory(src)) emptyList()
                    else Files.list(src).use { sets -> sets.toList() }
                        .filter { it.name == "test" || it.name.endsWith("Test") }
                        .flatMap { set -> Files.walk(set).use { walk -> walk.filter { it.name.endsWith(".kt") && it.name != "TestContainerRulesTest.kt" }.toList() } }   // 이 파일은 금지된 모양을 문자열로 담는다
                }
            }
        }

    /** (줄 번호, 이유). 주석 줄은 건너뛴다. */
    private fun containerBeanViolations(source: String): List<Pair<Int, String>> {
        val code = source.lines().map { line -> if (line.trim().startsWith("//") || line.trim().startsWith("*")) "" else line.substringBefore(" // ") }
        val hits = mutableListOf<Pair<Int, String>>()
        code.forEachIndexed { index, line ->
            if (Regex("""(?<![\w.])@ServiceConnection\b""").containsMatchIn(line)) hits += (index + 1) to "@ServiceConnection (container bean)"
        }
        // @Bean 다음에 오는 첫 `fun x(...): <Type>Container` — 애너테이션이 같은 줄 · 앞줄에 있는 경우 모두
        val joined = code.joinToString("\n")
        Regex("""@Bean\b[^{=]*?\bfun\s+\w+\s*\([^)]*\)\s*:\s*[\w.]*Container\b""").findAll(joined).forEach { match ->
            hits += (joined.substring(0, match.range.first).count { it == '\n' } + 1) to "@Bean returning a container"
        }
        return hits.distinct()
    }
}
