package dev.sumin.skeleton.common.capabilities

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * 카탈로그와 맞춰 볼 레포의 사실 — 코드와 빌드 파일에서 직접 읽는다 (카탈로그를 믿지 않는다).
 * 모듈 닫힘 규칙은 scripts/new-project.sh 의 `close` 와 같다: api · implementation · runtimeOnly · compileOnly · 테스트 의존을 모두 따라간다.
 */
internal class RepoFacts(val root: Path) {
    fun exists(rel: String): Boolean = root.resolve(rel).exists()

    fun read(rel: String): String = root.resolve(rel).readText()

    private fun dirsWithBuild(parent: String): List<String> {
        val dir = root.resolve(parent)
        if (!dir.isDirectory()) return emptyList()
        return Files.list(dir).use { it.filter { d -> d.isDirectory() && d.resolve("build.gradle.kts").exists() }.map { d -> d.name }.toList() }.sorted()
    }

    val modules: List<String> by lazy { dirsWithBuild("modules") }
    val apps: List<String> by lazy { dirsWithBuild("apps") }

    /** 한 build.gradle.kts 의 project(":modules:x") 의존 → (main | compile | test, x). 주석은 뺀다. */
    fun deps(buildRel: String): List<Pair<String, String>> {
        if (!exists(buildRel)) return emptyList()
        val re = Regex("""\b(api|implementation|runtimeOnly|compileOnly|testImplementation|testRuntimeOnly)\(project\(":modules:([a-z0-9-]+)"\)\)""")
        return read(buildRel).lines().map { it.substringBefore("//") }.flatMap { line ->
            re.findAll(line).map { m ->
                val cfg = m.groupValues[1]
                (if (cfg.startsWith("test")) "test" else if (cfg == "compileOnly") "compile" else "main") to m.groupValues[2]
            }.toList()
        }
    }

    fun appMainDeps(app: String): List<String> =
        deps("apps/$app/build.gradle.kts").filter { it.first == "main" }.map { it.second }.distinct()

    fun closure(start: Collection<String>): Set<String> {
        val out = linkedSetOf<String>()
        val queue = ArrayDeque(start)
        while (queue.isNotEmpty()) {
            val m = queue.removeFirst()
            if (!out.add(m)) continue
            deps("modules/$m/build.gradle.kts").forEach { queue.add(it.second) }
        }
        return out
    }

    val starterRaw: List<String> by lazy { appMainDeps("api") }
    val starterClosure: Set<String> by lazy { closure(starterRaw) }

    /** `--modules a,b --db mysql --with-sample` 같은 조각을 읽는다. */
    class Flag(val modules: List<String>, val mysql: Boolean, val sample: Boolean, val workbench: Boolean)

    fun parseFlag(flag: String?): Flag {
        val t = flag.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var modules = emptyList<String>()
        var mysql = false
        var i = 0
        while (i < t.size) {
            when (t[i]) {
                "--modules" -> { modules = t.getOrNull(i + 1).orEmpty().split(",").filter { it.isNotEmpty() }; i++ }
                "--db" -> { mysql = t.getOrNull(i + 1) == "mysql"; i++ }
            }
            i++
        }
        return Flag(modules, mysql, "--with-sample" in t, "--with-workbench" in t)
    }

    /** 이 조각으로 찍으면 남는 모듈 전부 — new-project.sh 의 SELECTED 와 같은 계산. */
    fun expectedModules(flag: String?): Set<String> {
        val f = parseFlag(flag)
        if (f.workbench) return modules.toSet()
        val starter = starterRaw.map { if (f.mysql && it == "db-postgresql") "db-mysql" else it }
        val sample = if (f.sample) appMainDeps("sample") else emptyList()
        return closure(starter + f.modules + sample)
    }

    /** 조각에서 의존으로 따라오는 것 = 남는 모듈 − 스타터 − 조각에 직접 적은 것. */
    fun autoIncludes(flag: String?): List<String> {
        val f = parseFlag(flag)
        val named = f.modules.toSet() + (if (f.mysql) setOf("db-mysql") else emptySet())
        return (expectedModules(flag) - starterClosure - named).sorted()
    }

    private fun mainSources(dirRel: String): List<Pair<Path, String>> {
        val src = root.resolve("$dirRel/src/main")
        if (!src.isDirectory()) return emptyList()
        return Files.walk(src).use { w -> w.filter { it.name.endsWith(".kt") }.toList() }.sorted().map { it to it.readText() }
    }

    /** `@ConfigurationProperties("a.b")` 의 접두사들. */
    fun prefixes(dirRel: String): Set<String> =
        Regex("""@ConfigurationProperties\(\s*(?:prefix\s*=\s*)?"([^"]+)"""").let { re ->
            mainSources(dirRel).flatMap { (_, text) -> re.findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        }

    /**
     * 코드가 여는 HTTP 경로 — 컨트롤러(클래스의 @RequestMapping, 없으면 메서드의 매핑)와 함수형 라우트(`route(POST("/…"))`)에서.
     * `${키:기본값}` 은 기본값으로, 첫 `{자리표시자}` 앞까지 자른 경로로 접는다.
     */
    fun basePaths(dirRel: String): List<String> {
        val classMapping = Regex("""@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"""")
        val methodMapping = Regex("""@(?:Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]+)"""")
        val functional = Regex("""\b(?:GET|POST|PUT|PATCH|DELETE)\("(/[^"]+)"\)""")
        val placeholder = Regex("""\\?\$\{[^:}]+:([^}]*)\}""")
        fun fold(raw: String): String = placeholder.replace(raw) { it.groupValues[1] }.substringBefore("{").trimEnd('/')
        return mainSources(dirRel).flatMap { (_, text) ->
            val raw = buildList {
                if (Regex("""(?m)^@RestController\b""").containsMatchIn(text)) {
                    val cls = classMapping.findAll(text).map { it.groupValues[1] }.toList()
                    addAll(cls.ifEmpty { methodMapping.findAll(text).map { it.groupValues[1] }.toList() })
                }
                addAll(functional.findAll(text).map { it.groupValues[1] })
            }
            raw.map(::fold)
        }.filter { it.isNotEmpty() }.distinct().sorted()
    }
}
