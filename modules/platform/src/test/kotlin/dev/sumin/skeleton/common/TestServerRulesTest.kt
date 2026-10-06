package dev.sumin.skeleton.common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 시험용 HTTP 서버는 **루프백 주소(127.0.0.1)에 묶는다** — `HttpServer.create(InetSocketAddress(0), 0)` 처럼 포트만 주면 모든 주소(와일드카드)에 묶이는데,
 * macOS 에서는 다른 프로세스가 같은 포트의 특정 주소(127.0.0.1)에 이미 듣고 있어도 와일드카드 바인딩이 성공하고, 127.0.0.1 로 오는 연결은 **그 다른 프로세스**로 간다.
 * 한 대를 여러 세션 · 에이전트가 같이 쓰는 호스트에서 `ExternalHttpClientTest` 가 가끔 `PrematureCloseException` · 엉뚱한 404 로 죽던 원인이다
 * (같은 시험 모양을 부하 아래 9300 번 돌려 와일드카드 9 번 실패 · 루프백 0 번 — CHANGELOG).
 */
class TestServerRulesTest {
    private val repoRoot: Path = Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })

    @Test
    fun `no test HttpServer binds the wildcard address`() {
        val violations = testSources(repoRoot).flatMap { file ->
            wildcardBinds(file.readText()).map { "${repoRoot.relativize(file)}:$it" }
        }
        assertEquals(emptyList(), violations, "InetSocketAddress(\"127.0.0.1\", 0) 으로 묶고 URL 도 http://127.0.0.1:<port> 로 쓴다 (docs/testing.md)")
    }

    @Test
    fun `the rule recognises the wildcard shapes and lets loopback pass`() {
        assertEquals(1, wildcardBinds("server = HttpServer.create(InetSocketAddress(0), 0)").size)
        assertEquals(1, wildcardBinds("HttpServer.create(InetSocketAddress(port), 0)").size)
        assertEquals(emptyList(), wildcardBinds("HttpServer.create(InetSocketAddress(\"127.0.0.1\", 0), 0)"))
        assertEquals(emptyList(), wildcardBinds("HttpServer.create(InetSocketAddress(\"localhost\", 0), 0)"))
        assertEquals(emptyList(), wildcardBinds("HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)"))
        assertEquals(emptyList(), wildcardBinds("// HttpServer.create(InetSocketAddress(0), 0) — 주석은 건너뛴다"))
    }

    private fun testSources(root: Path): List<Path> =
        listOf("apps", "modules").map { root.resolve(it) }.filter { Files.isDirectory(it) }.flatMap { top ->
            Files.list(top).use { children ->
                children.toList().flatMap { project ->
                    val src = project.resolve("src")
                    if (!Files.isDirectory(src)) emptyList()
                    else Files.list(src).use { sets -> sets.toList() }
                        .filter { it.name == "test" || it.name.endsWith("Test") }
                        .flatMap { set -> Files.walk(set).use { walk -> walk.filter { it.name.endsWith(".kt") && it.name != "TestServerRulesTest.kt" }.toList() } }
                }
            }
        }

    /** 줄 번호들 — `HttpServer.create(InetSocketAddress(<포트 하나만>), …)` */
    private fun wildcardBinds(source: String): List<Int> =
        source.lines().mapIndexedNotNull { i, line ->
            val code = if (line.trim().startsWith("//") || line.trim().startsWith("*")) "" else line.substringBefore(" // ")
            if (Regex("""HttpServer\.create\(\s*InetSocketAddress\(\s*[\w.]+\s*\)""").containsMatchIn(code)) i + 1 else null
        }
}
