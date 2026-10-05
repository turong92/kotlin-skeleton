package dev.sumin.skeleton.common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/minimal-composition.md` 의 "모듈 → 설정 접두사" 표는 코드에서 읽은 접두사와 같아야 한다.
 * 코드 쪽 정본: 모듈 main 의 `@ConfigurationProperties` 접두사 + `Binder` 로 직접 읽는 `skeleton.*` 경로
 * (같은 모듈의 `@ConfigurationProperties` 아래에 있는 Binder 경로는 그 접두사에 속하므로 따로 세지 않는다).
 *
 * 표에 있는 모듈 중 이 레포에 디렉토리가 없는 것은 건너뛴다 — 모듈을 덜어 낸 프로젝트(scripts/new-project.sh)에서도 통과해야 한다.
 */
class ConfigPrefixDocumentationTest {
    private val repoRoot: Path =
        Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })

    @Test
    fun `documented prefixes match the prefixes found in module code`() {
        val documented = documentedPrefixes()
        val present = scannedPrefixes()

        val differences = present.keys.sorted().mapNotNull { module ->
            val code = present.getValue(module)
            val doc = documented[module].orEmpty()
            if (code == doc) null
            else "$module: code=${code.sorted()} doc=${doc.sorted()}"
        }
        assertEquals(emptyList(), differences, "docs/minimal-composition.md 의 설정 접두사 표를 코드와 맞춘다")
    }

    @Test
    fun `every module with a configuration prefix has a copyable yml block in docs config modules`() {
        val missing = scannedPrefixes().keys.sorted()
            .filter { !Files.isRegularFile(repoRoot.resolve("docs/config/modules/$it.yml")) }
        assertEquals(emptyList(), missing, "docs/config/modules/<module>.yml — 모든 키와 기본값을 적은 블록 (apps/workbench ModuleConfigSnippetsTest 가 기본값과 맞춰 본다)")
    }

    @Test
    fun `no two modules claim the same prefix`() {
        val owners = scannedPrefixes().flatMap { (module, prefixes) -> prefixes.map { it to module } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertEquals(emptyMap(), owners)
    }

    @Test
    fun `the scan sees the prefixes this test exists to protect`() {
        // 표에서 빠졌던 것들: 플랫폼의 openapi, 모듈 이름과 접두사가 다른 idempotency, @ConfigurationProperties 없이 Binder 로 읽는 config-aws-ssm.
        // 모듈을 덜어 낸 프로젝트에서는 있는 모듈만 본다.
        val found = scannedPrefixes()
        assertTrue(found.getValue("platform").any { it.endsWith(".openapi") }, "scan result: $found")
        if (Files.isDirectory(repoRoot.resolve("modules/idempotency"))) {
            assertTrue(found.getValue("idempotency").any { it.endsWith(".idempotency") }, "scan result: $found")
        }
        if (Files.isDirectory(repoRoot.resolve("modules/config-aws-ssm"))) {
            assertTrue(found.getValue("config-aws-ssm").any { it.endsWith(".config.aws.ssm") }, "scan result: $found")
        }
    }

    /** module → 문서 표의 접두사들. 한 줄이 `| `module` | `skeleton.x` |` 인 행만 센다. */
    private fun documentedPrefixes(): Map<String, Set<String>> {
        val row = Regex("""^\|\s*`([a-z0-9-]+)`\s*\|\s*`([a-z][a-z0-9-]*(?:\.[a-z0-9-]+)+)`\s*\|\s*$""")
        return repoRoot.resolve("docs/minimal-composition.md").readText().lines()
            .mapNotNull { row.matchEntire(it)?.destructured?.let { (module, prefix) -> module to prefix } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }

    private fun scannedPrefixes(): Map<String, Set<String>> {
        val configurationProperties = Regex("""@ConfigurationProperties\(\s*(?:prefix\s*=\s*)?"([^"]+)"""")
        val binderRead = Regex("""\.bind\(\s*"([a-z][a-z0-9-]*\.[^"]+)"""")
        val sourcesByModule = moduleDirectories().associate { module ->
            module.name to kotlinSources(module.resolve("src/main")).map { it.readText() }
        }
        val declaredByModule = sourcesByModule.mapValues { (_, sources) ->
            sources.flatMap { text -> configurationProperties.findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        }
        // 설정 루트 키(스켈레톤은 skeleton, rename 뒤에는 새 접두사): 선언된 접두사의 첫 마디 중 가장 흔한 것.
        // Binder 로 읽는 spring.* 같은 남의 키는 이 루트 아래가 아니므로 센다.
        val root = declaredByModule.values.flatten().groupingBy { it.substringBefore('.') }.eachCount().maxByOrNull { it.value }!!.key
        return sourcesByModule.mapValues { (module, sources) ->
            val declared = declaredByModule.getValue(module)
            val bound = sources.flatMap { text -> binderRead.findAll(text).map { it.groupValues[1] }.toList() }
                .filter { path -> path.startsWith("$root.") }
                .filter { path -> declared.none { path == it || path.startsWith("$it.") } }
                .toSet()
            declared + bound
        }.filterValues { it.isNotEmpty() }
    }

    private fun moduleDirectories(): List<Path> =
        Files.list(repoRoot.resolve("modules")).use { it.filter { dir -> Files.isDirectory(dir) }.toList() }

    private fun kotlinSources(root: Path): List<Path> =
        if (!Files.isDirectory(root)) emptyList()
        else Files.walk(root).use { walk -> walk.filter { it.name.endsWith(".kt") }.toList() }
}
