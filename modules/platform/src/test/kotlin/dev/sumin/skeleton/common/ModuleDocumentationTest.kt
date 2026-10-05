package dev.sumin.skeleton.common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/modules/<module>.md` — 모듈마다 정해진 틀의 한 쪽짜리 문서 — 가 코드와 어긋나지 않는지 본다.
 *
 * - `settings.gradle.kts` 의 모든 `:modules:*` 에 문서가 있고, 문서에 짝 없는 모듈이 없고, 색인(README.md)이 둘을 그대로 가리킨다.
 * - 문서가 말하는 것(의존성 · 함께 오는 모듈 · 설정 접두사 · 교체 지점 · 마이그레이션 · 테스트 경로)이 코드에 실제로 있다.
 *
 * scripts/new-project.sh 로 찍은 프로젝트(모듈 일부 · 루트 패키지 이름 변경)에서도 통과해야 한다 — 찍을 때 지운 모듈의 문서와 색인 행도 같이 지운다.
 * 이 파일은 `skeleton` 을 코드 식별자로 하드코딩하지 않는다 (rename 이 접두사를 바꿔도 같은 규칙으로 읽는다).
 */
class ModuleDocumentationTest {
    private val repoRoot: Path =
        Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })
    private val pagesDir: Path = repoRoot.resolve("docs/modules")

    private val requiredRows = listOf(
        "의존성 한 줄", "함께 오는 모듈", "컴파일 전용", "설정 접두사", "기본 동작", "부팅에 필요한 것",
        "교체 지점", "마이그레이션", "프론트 짝", "테스트",
    )

    @Test
    fun `every module in settings has a page and every page has a module`() {
        val pages = pageNames()
        assertEquals(emptyList(), modules().filter { it !in pages }, "docs/modules/<module>.md 가 없는 모듈")
        assertEquals(emptyList(), pages.filter { it !in modules() }, "settings.gradle.kts 에 없는 모듈의 문서 (찍을 때 같이 지워야 한다)")
    }

    @Test
    fun `the index lists exactly the pages`() {
        val index = pagesDir.resolve("README.md")
        assertTrue(index.exists(), "docs/modules/README.md (모듈 색인)")
        val listed = Regex("""\]\(([a-z0-9-]+)\.md\)""").findAll(index.readText()).map { it.groupValues[1] }.toList()
        assertEquals(pageNames().sorted(), listed.sorted(), "색인 표는 문서마다 정확히 한 줄")
    }

    @Test
    fun `every page follows the template`() {
        val problems = pageNames().flatMap { module ->
            val text = page(module).readText()
            val rows = rows(module)
            buildList {
                if (!text.startsWith("# $module\n")) add("$module: 첫 줄은 '# $module'")
                requiredRows.filter { it !in rows }.forEach { add("$module: 행 '$it' 없음") }
                val behavior = rows["기본 동작"].orEmpty()
                if (!Regex("^(켜짐|꺼짐|조건부)").containsMatchIn(behavior)) add("$module: '기본 동작' 은 켜짐/꺼짐/조건부 로 시작: $behavior")
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the dependency line and the modules it brings match the build file`() {
        val problems = pageNames().flatMap { module ->
            val rows = rows(module)
            val (runtime, compileOnly) = dependencyClosure(module)
            buildList {
                if ("""implementation(project(":modules:$module"))""" !in code(rows["의존성 한 줄"])) add("$module: 의존성 한 줄이 틀리다")
                val documented = code(rows["함께 오는 모듈"]).toSet()
                if (documented != runtime) add("$module: 함께 오는 모듈 doc=${documented.sorted()} build=${runtime.sorted()}")
                val documentedCompileOnly = code(rows["컴파일 전용"]).toSet()
                if (documentedCompileOnly != compileOnly) add("$module: 컴파일 전용 doc=${documentedCompileOnly.sorted()} build=${compileOnly.sorted()}")
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `configuration prefixes on a page exist in the module code and none is missing`() {
        val problems = pageNames().flatMap { module ->
            val sources = mainSources(module)
            val documented = code(rows(module)["설정 접두사"]).filter { '.' in it }.toSet()
            val declared = Regex("""@ConfigurationProperties\(\s*(?:prefix\s*=\s*)?"([^"]+)"""").findAll(sources)
                .map { it.groupValues[1] }.toSet()
            buildList {
                documented.filter { "\"$it\"" !in sources }.forEach { add("$module: 코드에 없는 접두사 $it") }
                declared.filter { it !in documented }.forEach { add("$module: 문서에 없는 접두사 $it") }
                val yml = repoRoot.resolve("docs/config/modules/$module.yml")
                val link = "../config/modules/$module.yml"
                if (yml.exists() && link !in page(module).readText()) add("$module: $link 링크가 없다")
                if (!yml.exists() && documented.isEmpty() && rows(module)["설정 접두사"]?.startsWith("없음") != true) add("$module: 접두사가 없으면 '없음'")
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `override points on a page are types the module code declares or uses`() {
        val declaredTypes = Regex("""\b(?:class|interface|object|typealias)\s+([A-Z][A-Za-z0-9_]*)""")
        val allDeclared = modules().flatMap { declaredTypes.findAll(mainSources(it)).map { m -> m.groupValues[1] }.toList() }.toSet()
        val problems = pageNames().flatMap { module ->
            val sources = mainSources(module)
            val types = code(rows(module)["교체 지점"])
            buildList {
                // 대문자로 시작하면 타입 (이 레포가 선언했거나 모듈이 import), 소문자로 시작하면 빈 이름 (모듈 소스에 그 단어가 있다)
                types.filter { it.first().isUpperCase() && it !in allDeclared && !Regex("""import\s+[\w.]+\.$it\b""").containsMatchIn(sources) }
                    .forEach { add("$module: 코드에 없는 교체 지점 타입 $it") }
                types.filter { it.first().isLowerCase() && !Regex("""\b$it\b""").containsMatchIn(sources) }
                    .forEach { add("$module: 코드에 없는 교체 지점 빈 이름 $it") }
                if (types.isNotEmpty() && "ConditionalOnMissingBean" !in sources) add("$module: @ConditionalOnMissingBean 이 없는데 교체 지점을 적었다")
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `paths named for migrations and tests exist`() {
        val problems = pageNames().flatMap { module ->
            val rows = rows(module)
            val migrationFiles = Files.walk(repoRoot.resolve("modules/$module/src/main/resources")).use { walk ->
                walk.filter { it.name.endsWith(".sql") }.toList()
            }.size
            buildList {
                (code(rows["마이그레이션"]) + code(rows["테스트"])).filter { '/' in it }.forEach { path ->
                    if (!repoRoot.resolve(path).exists()) add("$module: 없는 경로 $path")
                }
                if (migrationFiles > 0 && rows["마이그레이션"]?.startsWith("없음") == true) add("$module: 마이그레이션이 있는데 '없음'")
                if (migrationFiles == 0 && rows["마이그레이션"]?.startsWith("없음") != true) add("$module: 마이그레이션이 없는데 적었다")
                if (code(rows["테스트"]).none { '/' in it }) add("$module: 테스트 경로가 없다")
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `frontend counterparts are known react-skeleton packages or none`() {
        val known = setOf("@skeleton/api-client", "@skeleton/auth", "@skeleton/realtime", "@skeleton/time")
        val problems = pageNames().flatMap { module ->
            val cell = rows(module)["프론트 짝"].orEmpty()
            val tokens = code(cell)
            buildList {
                if (tokens.isEmpty() && !cell.startsWith("없음")) add("$module: 프론트 짝은 패키지 이름이거나 '없음'")
                tokens.filter { it !in known }.forEach { add("$module: 모르는 프론트 패키지 $it") }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `relative links on pages resolve`() {
        val link = Regex("""\]\((?!https?://|#)([^)#\s]+)""")
        val pages = if (pagesDir.isDirectory()) Files.list(pagesDir).use { it.filter { p -> p.name.endsWith(".md") }.toList() } else emptyList()
        val problems = pages.flatMap { page ->
            link.findAll(page.readText()).map { it.groupValues[1] }.filter { !page.parent.resolve(it).normalize().exists() }
                .map { "${page.name}: 깨진 링크 $it" }.toList()
        }
        assertEquals(emptyList(), problems)
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun modules(): List<String> =
        Regex("""include\(":modules:([a-z0-9-]+)"\)""").findAll(repoRoot.resolve("settings.gradle.kts").readText())
            .map { it.groupValues[1] }.toList()

    private fun pageNames(): List<String> =
        if (!pagesDir.isDirectory()) emptyList()
        else Files.list(pagesDir).use { it.map { p -> p.name }.filter { n -> n.endsWith(".md") && n != "README.md" }.toList() }
            .map { it.removeSuffix(".md") }.sorted()

    private fun page(module: String): Path = pagesDir.resolve("$module.md")

    /** 표의 `| 항목 | 내용 |` 행 → 항목 → 내용 (내용 안의 `\|` 는 `|` 로). */
    private fun rows(module: String): Map<String, String> =
        page(module).readText().lines().mapNotNull { Regex("""^\|\s*([^|]+?)\s*\|\s*(.*?)\s*\|\s*$""").matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\|", "|") }

    /** 칸 안의 `백틱` 토큰들. */
    private fun code(cell: String?): List<String> =
        Regex("`([^`]+)`").findAll(cell.orEmpty()).map { it.groupValues[1] }.toList()

    private fun mainSources(module: String): String {
        val root = repoRoot.resolve("modules/$module/src/main")
        if (!root.isDirectory()) return ""
        return Files.walk(root).use { walk -> walk.filter { it.name.endsWith(".kt") }.toList() }.joinToString("\n") { it.readText() }
    }

    /** (런타임 전이 모듈 전체, 컴파일 전용 모듈) — build.gradle.kts 의 project(":modules:x") 줄에서. 테스트용 의존은 뺀다. */
    private fun dependencyClosure(module: String): Pair<Set<String>, Set<String>> {
        fun direct(m: String): List<Pair<String, String>> {
            val build = repoRoot.resolve("modules/$m/build.gradle.kts")
            if (!build.exists()) return emptyList()
            return build.readText().lines().map { it.substringBefore("//") }.flatMap { line ->
                Regex("""\b(api|implementation|runtimeOnly|compileOnly)\(project\(":modules:([a-z0-9-]+)"\)\)""").findAll(line)
                    .map { it.groupValues[1] to it.groupValues[2] }.toList()
            }
        }
        val runtime = linkedSetOf<String>()
        val queue = ArrayDeque(direct(module).filter { it.first != "compileOnly" }.map { it.second })
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (runtime.add(next)) queue.addAll(direct(next).filter { it.first != "compileOnly" }.map { it.second })
        }
        return runtime to direct(module).filter { it.first == "compileOnly" }.map { it.second }.toSet()
    }
}
