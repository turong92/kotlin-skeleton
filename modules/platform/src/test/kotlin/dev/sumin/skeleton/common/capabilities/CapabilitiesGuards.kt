package dev.sumin.skeleton.common.capabilities

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * capabilities.json 가드들. 가드 하나 = 함수 하나, 반환값은 문제 목록(비어 있으면 통과).
 * 문제 문장은 「무엇이 틀렸나 · 맞는 값은 무엇인가」까지 적는다 — 항목이 없으면 붙일 객체를 그대로 보여 준다.
 */
internal object CapabilitiesGuards {
    private fun entries(catalog: Map<String, Any?>): List<Map<String, Any?>> = catalog["capabilities"].arr().map { it.obj() }

    private fun moduleEntries(catalog: Map<String, Any?>) = entries(catalog).filter { it["kind"] == "module" }

    private fun label(e: Map<String, Any?>) = "'${e["id"]}'"

    // ---------------------------------------------------------------------------------------------------- 1. 모듈 · 앱 · 스크립트 ↔ 항목

    fun coverage(catalog: Map<String, Any?>, facts: RepoFacts): List<String> {
        val all = entries(catalog)
        val problems = mutableListOf<String>()
        val addHint = "capabilities.json 의 \"capabilities\" 배열에 아래 객체를 붙이고 TODO 를 채운다(계산할 수 있는 칸은 레포에서 채웠다 — summary · notFor · keywords · needs · frontend · secrets · status 는 사람이 정한다):"
        facts.modules.filter { m -> all.none { it["kind"] == "module" && it["module"] == m } }.forEach { m ->
            problems += "modules/$m 의 항목이 없다. $addHint\n${Json.pretty(stubModule(m, facts))}"
        }
        facts.apps.filter { a -> all.none { it["kind"] == "app" && it["path"] == "apps/$a" } }.forEach { a ->
            problems += "apps/$a 의 항목이 없다. $addHint\n${Json.pretty(stubApp(a, facts))}"
        }
        scriptFiles(facts).filter { s -> all.none { it["kind"] == "script" && it["path"] == "scripts/$s" } }.forEach { s ->
            problems += "scripts/$s 의 항목이 없다. $addHint\n${Json.pretty(stubScript(s))}"
        }
        all.filter { it["kind"] == "module" }.filter { it["module"] !in facts.modules }
            .forEach { problems += "항목 ${label(it)} 의 모듈 modules/${it["module"]} 이(가) 레포에 없다 — 항목을 지운다(모듈을 지웠다면 같이)" }
        all.filter { it["kind"] == "app" }.filter { !facts.exists("${it["path"]}/build.gradle.kts") }
            .forEach { problems += "항목 ${label(it)} 의 앱 ${it["path"]} 이(가) 레포에 없다 — 항목을 지운다" }
        all.filter { it["kind"] == "script" }.filter { !facts.exists(it["path"] as String) }
            .forEach { problems += "항목 ${label(it)} 의 스크립트 ${it["path"]} 이(가) 레포에 없다 — 항목을 지운다" }
        return problems
    }

    private fun scriptFiles(facts: RepoFacts): List<String> {
        val dir = facts.root.resolve("scripts")
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { it.filter { p -> p.isRegularFile() && (p.name.endsWith(".sh") || p.name.endsWith(".pl")) }.map { p -> p.name }.toList() }.sorted()
    }

    private fun stubModule(m: String, facts: RepoFacts): Map<String, Any?> {
        val starter = m in facts.starterClosure
        val flag = if (starter) null else if (m == "db-mysql") "--db mysql" else "--modules $m"
        val prefixes = facts.prefixes("modules/$m").toList().sorted()
        val snippet = "docs/config/modules/$m.yml".takeIf { facts.exists(it) }
        val packages = Regex("`(@[^`]+)`").findAll(
            Regex("""\| 프론트 짝 \| (.*?) \|""").find(if (facts.exists("docs/modules/$m.md")) facts.read("docs/modules/$m.md") else "")?.groupValues?.get(1).orEmpty(),
        ).map { it.groupValues[1] }.toList()
        return linkedMapOf(
            "id" to m, "kind" to "module", "module" to m, "path" to "modules/$m", "status" to "experimental",
            "summary" to "TODO 한 문장 — 쓰는 사람이 무엇을 얻는가",
            "starter" to starter, "newProjectFlag" to flag, "autoIncludes" to facts.autoIncludes(flag),
            "needs" to linkedMapOf(
                "requires" to emptyList<String>(), "oneOf" to emptyList<List<String>>(),
                "optional" to facts.deps("modules/$m/build.gradle.kts").filter { it.first == "compile" }.map { it.second }.distinct().sorted(),
            ),
            "config" to if (prefixes.isEmpty() && snippet == null) null else linkedMapOf("prefixes" to prefixes, "snippet" to snippet),
            "basePaths" to facts.basePaths("modules/$m"), "secrets" to emptyList<Any?>(),
            "docs" to listOf("docs/modules/$m.md"),
            "frontend" to if (packages.isEmpty()) null else linkedMapOf("capabilities" to listOf("TODO"), "packages" to packages, "newProjectFlag" to null),
            "notFor" to listOf("TODO 이 항목이 맞지 않는 경우"),
            "keywords" to linkedMapOf("ko" to listOf("TODO"), "en" to listOf("TODO")),
        )
    }

    private fun stubApp(a: String, facts: RepoFacts): Map<String, Any?> {
        val flag = when (a) { "sample" -> "--with-sample"; "workbench" -> "--with-workbench"; else -> null }
        return linkedMapOf(
            "id" to "app-$a", "kind" to "app", "module" to null, "path" to "apps/$a", "status" to "stable",
            "summary" to "TODO 한 문장", "starter" to (a == "api"), "newProjectFlag" to flag, "autoIncludes" to facts.autoIncludes(flag),
            "needs" to linkedMapOf("requires" to emptyList<String>(), "oneOf" to emptyList<List<String>>(), "optional" to emptyList<String>()),
            "config" to null, "basePaths" to facts.basePaths("apps/$a"), "secrets" to emptyList<Any?>(),
            "docs" to listOf("docs/minimal-composition.md"), "frontend" to null,
            "notFor" to listOf("TODO"), "keywords" to linkedMapOf("ko" to listOf("TODO"), "en" to listOf("TODO")),
        )
    }

    private fun stubScript(file: String): Map<String, Any?> = linkedMapOf(
        "id" to "script-${file.substringBeforeLast('.')}", "kind" to "script", "module" to null, "path" to "scripts/$file", "status" to "stable",
        "summary" to "TODO 한 문장", "starter" to false, "newProjectFlag" to null, "autoIncludes" to emptyList<String>(),
        "needs" to linkedMapOf("requires" to emptyList<String>(), "oneOf" to emptyList<List<String>>(), "optional" to emptyList<String>()),
        "config" to null, "basePaths" to emptyList<String>(), "secrets" to emptyList<Any?>(),
        "docs" to listOf("README.md"), "frontend" to null,
        "notFor" to listOf("TODO"), "keywords" to linkedMapOf("ko" to listOf("TODO"), "en" to listOf("TODO")),
    )

    // ---------------------------------------------------------------------------------------------------- 2. 스키마

    fun schema(catalog: Map<String, Any?>, schema: Map<String, Any?>): List<String> {
        val problems = mutableListOf<String>()
        validate(catalog, schema, schema, "$", problems)
        return problems
    }

    private fun validate(value: Any?, s: Map<String, Any?>, rootSchema: Map<String, Any?>, at: String, out: MutableList<String>) {
        val ref = s["\$ref"] as? String
        val base = if (ref != null) resolve(ref, rootSchema) else null
        val schema = if (base != null) base + s.filterKeys { it != "\$ref" && it != "description" } else s
        s["const"]?.let { if (value != it && value.toString() != it.toString()) out += "$at: $it 여야 한다 (실제 $value)" }
        (schema["enum"] as? List<*>)?.let { if (value !in it) out += "$at: ${it.joinToString(" | ")} 중 하나여야 한다 (실제 ${Json.pretty(value).trim()})" }
        val types = when (val t = schema["type"]) { is String -> listOf(t); is List<*> -> t.map { it as String }; else -> null }
        if (types != null && types.none { typeOk(value, it) }) {
            out += "$at: 타입은 ${types.joinToString(" | ")} 여야 한다 (실제 ${Json.pretty(value).trim().take(60)})"
            return
        }
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST") val obj = value as Map<String, Any?>
            schema["required"].strings().filter { it !in obj }.forEach { out += "$at: 필수 칸 '$it' 이(가) 없다" }
            val props = (schema["properties"] as? Map<*, *>).orEmpty()
            if (schema["additionalProperties"] == false) obj.keys.filter { it !in props }.forEach { out += "$at: 모르는 칸 '$it' (스키마에 없다)" }
            props.forEach { (k, sub) -> if (k in obj) validate(obj[k as String], sub.obj(), rootSchema, "$at.$k", out) }
        }
        if (value is List<*>) {
            (schema["minItems"] as? Int)?.let { if (value.size < it) out += "$at: 항목이 ${it}개 이상이어야 한다 (실제 ${value.size})" }
            (schema["items"] as? Map<*, *>)?.let { items -> value.forEachIndexed { i, v -> validate(v, items.obj(), rootSchema, "$at[$i]", out) } }
        }
        if (value is String) {
            (schema["minLength"] as? Int)?.let { if (value.length < it) out += "$at: 길이가 $it 이상이어야 한다" }
            (schema["pattern"] as? String)?.let { if (!Regex(it).containsMatchIn(value)) out += "$at: '$value' 은(는) $it 에 맞아야 한다" }
        }
    }

    private fun typeOk(v: Any?, t: String) = when (t) {
        "object" -> v is Map<*, *>
        "array" -> v is List<*>
        "string" -> v is String
        "boolean" -> v is Boolean
        "integer" -> v is Int || v is Long
        "number" -> v is Number
        "null" -> v == null
        else -> true
    }

    private fun resolve(ref: String, root: Map<String, Any?>): Map<String, Any?> {
        var cur: Any? = root
        ref.removePrefix("#/").split("/").forEach { cur = (cur as Map<*, *>)[it] }
        return cur.obj()
    }

    // ---------------------------------------------------------------------------------------------------- 3. 항목끼리의 일관성

    fun entries(catalog: Map<String, Any?>, facts: RepoFacts): List<String> {
        val all = entries(catalog)
        val problems = mutableListOf<String>()
        val ids = all.map { it["id"] as String }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "id 중복: $it" }
        val idSet = ids.toSet()
        all.forEach { e ->
            val id = e["id"] as String
            val path = e["path"] as? String
            when (e["kind"]) {
                "module" -> {
                    if (e["module"] != id) problems += "${label(e)}: kind=module 인데 id 가 module(${e["module"]})와 다르다 — id 는 모듈 이름 그대로"
                    if (path != "modules/${e["module"]}") problems += "${label(e)}: path 는 modules/${e["module"]} 여야 한다 (실제 $path)"
                }
                "app" -> {
                    if (e["module"] != null) problems += "${label(e)}: kind=app 의 module 은 null"
                    if (path == null || id != "app-${path.removePrefix("apps/")}") problems += "${label(e)}: id 는 app-<디렉토리 이름> 이어야 한다 (path $path)"
                }
                "script" -> {
                    if (e["module"] != null) problems += "${label(e)}: kind=script 의 module 은 null"
                    if (path == null || id != "script-${path.removePrefix("scripts/").substringBeforeLast('.')}") problems += "${label(e)}: id 는 script-<파일 이름(확장자 없이)> 이어야 한다 (path $path)"
                }
            }
            val needs = e["needs"].obj()
            (needs["requires"].strings() + needs["optional"].strings() + needs["oneOf"].arr().flatMap { it.strings() } + e["autoIncludes"].strings())
                .filter { it !in idSet }.distinct().forEach { problems += "${label(e)}: 항목에 없는 id '$it' 를 가리킨다(needs · autoIncludes) — 이름을 고치거나 그 모듈의 항목을 만든다" }
            (e["frontend"] as? Map<*, *>)?.let { fe ->
                if (fe["capabilities"].strings().isEmpty()) problems += "${label(e)}: frontend 가 있으면 capabilities(짝 레포의 id)를 적는다"
            }
            if (e["kind"] == "module") {
                val starter = id in facts.starterClosure
                if (e["starter"] != starter) problems += "${label(e)}: starter 는 $starter 여야 한다 (apps/api 의 의존 닫힘에 ${if (starter) "있다" else "없다"})"
            }
        }
        val real = facts.starterClosure.sorted()
        if (catalog["starterModules"].strings() != real) problems += "starterModules 는 $real 여야 한다 (apps/api 의존의 닫힘). 지금: ${catalog["starterModules"].strings()}"
        return problems
    }

    // ---------------------------------------------------------------------------------------------------- 4. 조각 · autoIncludes

    class DryRun(val exit: Int, val modules: Set<String>, val output: String)

    /** `scripts/new-project.sh --dry-run` — 계획만 찍고 아무것도 쓰지 않는다. */
    fun dryRun(root: Path, flag: String): DryRun {
        val target = Files.createTempDirectory("caps-dry").resolve("out").toString()
        val cmd = mutableListOf("bash", "scripts/new-project.sh", "--dry-run", target, "dev.example.x", "x", "X") + flag.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val p = ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return DryRun(-1, emptySet(), "timeout\n$text") }
        val line = Regex("""(?m)^\s*modules \(\d+\): (.*)$""").find(text)?.groupValues?.get(1).orEmpty()
        return DryRun(p.exitValue(), line.split(" ").filter { it.isNotEmpty() }.toSet(), text)
    }

    private fun stamped(catalog: Map<String, Any?>) = catalog["mode"] == "stamped"

    fun stampFlags(catalog: Map<String, Any?>, facts: RepoFacts, runScript: Boolean): List<String> {
        if (stamped(catalog)) return emptyList()   // 찍은 프로젝트에는 조각이 없다(newProjectFlag 는 null) — 스켈레톤에서만 의미가 있다
        val problems = mutableListOf<String>()
        val checked = mutableSetOf<String>()
        entries(catalog).filter { it["kind"] != "script" }.forEach { e ->
            val flag = e["newProjectFlag"] as? String
            val id = e["id"] as String
            if (e["kind"] == "module" && e["starter"] == true) {
                if (flag != null) problems += "${label(e)}: starter 모듈인데 newProjectFlag 가 있다(스타터가 이미 가진다) — null 로"
                return@forEach
            }
            if (e["kind"] == "module" && flag == null) { problems += "${label(e)}: starter 가 아닌 모듈은 newProjectFlag 가 있어야 한다(예: --modules $id)"; return@forEach }
            val f = facts.parseFlag(flag)
            when {
                e["kind"] == "app" && id == "app-api" -> if (flag != null) problems += "${label(e)}: 스타터 앱은 조각이 없다"
                e["kind"] == "app" -> {
                    val want = "--with-${id.removePrefix("app-")}"
                    if (flag != want) problems += "${label(e)}: newProjectFlag 는 $want 여야 한다 (실제 $flag)"
                }
                id == "db-mysql" -> if (flag != "--db mysql") problems += "${label(e)}: newProjectFlag 는 --db mysql 이어야 한다 (db-* 는 --modules 가 아니라 --db)"
                else -> {
                    val want = (listOf(id) + e["needs"].obj()["requires"].strings()).toSet()
                    if (f.modules.toSet() != want) problems += "${label(e)}: newProjectFlag 의 모듈은 id + needs.requires = ${want.sorted()} 여야 한다 (실제 ${f.modules.sorted()})"
                }
            }
            val real = facts.autoIncludes(flag)
            if (e["autoIncludes"].strings().sorted() != real) {
                problems += "${label(e)}: autoIncludes 는 ${Json.pretty(real).replace(Regex("\\s+"), " ")} 여야 한다 (Gradle 의존 닫힘 — 스타터 · 조각에 적은 모듈 제외). 지금: ${e["autoIncludes"].strings()}"
            }
            if (runScript && flag != null && checked.add(flag)) {
                val run = dryRun(facts.root, flag)
                if (run.exit != 0) problems += "${label(e)}: new-project.sh 가 '$flag' 를 받지 않는다 (exit ${run.exit}):\n${run.output.lines().take(6).joinToString("\n") { "    $it" }}"
                else if (run.modules != facts.expectedModules(flag)) {
                    problems += "${label(e)}: new-project.sh '$flag' 가 남기는 모듈이 계산과 다르다 — 스크립트 ${run.modules.sorted()} / 계산 ${facts.expectedModules(flag).sorted()}"
                }
            }
        }
        return problems
    }

    // ---------------------------------------------------------------------------------------------------- 5. 설정 접두사

    fun config(catalog: Map<String, Any?>, facts: RepoFacts): List<String> {
        val problems = mutableListOf<String>()
        moduleEntries(catalog).forEach { e ->
            val m = e["module"] as String
            val declared = facts.prefixes("modules/$m")
            val cfg = e["config"] as? Map<*, *>
            val listed = cfg?.get("prefixes").strings().toSet()
            if (listed != declared) {
                problems += "${label(e)}: config.prefixes 는 ${declared.sorted()} 여야 한다(모듈 코드의 @ConfigurationProperties). 지금: ${listed.sorted()}" +
                    if (declared.isEmpty()) " — 접두사가 없으면 config 를 null 로(다른 모듈 블록에 두는 설정이면 prefixes [] + note)" else ""
            }
            val snippetFile = "docs/config/modules/$m.yml"
            val want = snippetFile.takeIf { facts.exists(it) }
            val got = cfg?.get("snippet") as? String
            if (cfg != null && got != want) problems += "${label(e)}: config.snippet 은 ${want ?: "null(그 파일이 없다)"} 여야 한다 (실제 $got)"
            if (cfg == null && want != null) problems += "${label(e)}: $snippetFile 이 있는데 config 가 null 이다"
        }
        return problems
    }

    // ---------------------------------------------------------------------------------------------------- 6. HTTP 경로

    fun basePaths(catalog: Map<String, Any?>, facts: RepoFacts): List<String> {
        val problems = mutableListOf<String>()
        entries(catalog).filter { it["kind"] == "module" || it["kind"] == "app" }.forEach { e ->
            val real = facts.basePaths(e["path"] as String)
            val listed = e["basePaths"].strings().sorted()
            if (listed != real) problems += "${label(e)}: basePaths 는 ${Json.pretty(real).replace(Regex("\\s+"), " ")} 여야 한다(컨트롤러 · 라우트의 매핑에서 계산, 첫 {자리표시자} 앞까지). 지금: $listed"
        }
        return problems
    }

    // ---------------------------------------------------------------------------------------------------- 7. 문서 경로

    fun docs(catalog: Map<String, Any?>, facts: RepoFacts): List<String> {
        val problems = mutableListOf<String>()
        entries(catalog).forEach { e ->
            if (!facts.exists(e["path"] as String)) problems += "${label(e)}: path ${e["path"]} 이(가) 없다"
            e["docs"].strings().filter { !facts.exists(it) }.forEach { problems += "${label(e)}: docs 의 $it 이(가) 없다" }
            if (e["kind"] == "module" && "docs/modules/${e["module"]}.md" !in e["docs"].strings()) {
                problems += "${label(e)}: docs 에 모듈 쪽 문서 docs/modules/${e["module"]}.md 가 있어야 한다"
            }
            if (e["kind"] == "module" && facts.exists("docs/modules/${e["module"]}.md")) {
                // 모듈 문서의 「프론트 짝」 행과 카탈로그의 frontend.packages 는 같은 말이어야 한다 (한쪽만 고쳐 어긋나기 쉽다)
                val row = Regex("""\| 프론트 짝 \| (.*?) \|""").find(facts.read("docs/modules/${e["module"]}.md"))?.groupValues?.get(1).orEmpty()
                val onPage = Regex("`(@[^`]+)`").findAll(row).map { it.groupValues[1] }.toSet()
                val inCatalog = ((e["frontend"] as? Map<*, *>)?.get("packages")).strings().toSet()
                if (onPage != inCatalog) {
                    problems += "${label(e)}: docs/modules/${e["module"]}.md 의 「프론트 짝」 ${onPage.sorted().ifEmpty { listOf("없음") }} 과 카탈로그 frontend.packages ${inCatalog.sorted()} 이(가) 다르다 — 둘을 맞춘다"
                }
            }
        }
        catalog["guides"].arr().map { it.obj() }.filter { !facts.exists(it["path"] as String) }.forEach { problems += "guides 의 ${it["path"]} 이(가) 없다" }
        return problems
    }

    // ---------------------------------------------------------------------------------------------------- 8. 비밀 ↔ docs/deploy.md §7

    /** docs/deploy.md 「모듈별 비밀」 표가 말하는 모듈들. `auth-social-google / -kakao` 는 접미사 묶음, `redis-*` 는 redis-core 하나(나머지 redis 모듈은 같은 값을 쓴다). */
    fun deployTableModules(deployMd: String): Set<String> {
        val section = deployMd.substringAfter("## 7.", "").substringBefore("\n## ")
        val out = linkedSetOf<String>()
        section.lines().filter { it.startsWith("| ") && !it.startsWith("| 모듈") && !it.startsWith("|---") }.forEach { row ->
            val first = row.split("|").getOrNull(1).orEmpty().trim()
            val tokens = first.split(" / ").map { it.trim() }
            var head = tokens.first()
            if (head == "redis-*") { out += "redis-core"; return@forEach }
            out += head
            tokens.drop(1).forEach { t ->
                if (t.startsWith("-")) out += head.substringBeforeLast('-') + t else { out += t; head = t }
            }
        }
        return out
    }

    fun secrets(catalog: Map<String, Any?>, facts: RepoFacts): List<String> {
        if (!facts.exists("docs/deploy.md")) return emptyList()
        val table = deployTableModules(facts.read("docs/deploy.md"))
        val problems = mutableListOf<String>()
        moduleEntries(catalog).forEach { e ->
            val m = e["module"] as String
            val hasSecrets = e["secrets"].arr().isNotEmpty()
            if (m in table && !hasSecrets) problems += "${label(e)}: docs/deploy.md §7 표에 있는 모듈인데 secrets 가 비었다 — 환경변수 이름과 빠졌을 때의 동작을 적는다"
            if (m !in table && hasSecrets) problems += "${label(e)}: secrets 가 있는데 docs/deploy.md §7 표에 이 모듈이 없다 — 표에 행을 더하거나 secrets 를 지운다"
        }
        return problems
    }

    // ---------------------------------------------------------------------------------------------------- 9. 키워드 · 자리표시자

    fun keywords(catalog: Map<String, Any?>): List<String> {
        val problems = mutableListOf<String>()
        entries(catalog).forEach { e ->
            val kw = e["keywords"] as? Map<*, *>
            listOf("ko", "en").forEach { lang -> if (kw?.get(lang).strings().isEmpty()) problems += "${label(e)}: keywords.$lang 이(가) 비었다 — 사용자가 쓸 말을 적는다" }
            todos(e, "${label(e)}", problems)
        }
        return problems
    }

    private fun todos(v: Any?, at: String, out: MutableList<String>) {
        when (v) {
            is String -> if (v.contains("TODO")) out += "$at: TODO 가 남아 있다 — '${v.take(50)}'"
            is Map<*, *> -> v.forEach { (k, x) -> todos(x, "$at.$k", out) }
            is List<*> -> v.forEachIndexed { i, x -> todos(x, "$at[$i]", out) }
        }
    }

    // ---------------------------------------------------------------------------------------------------- 10. 결정표 · 예 · 레시피

    fun decisions(catalog: Map<String, Any?>, facts: RepoFacts, runScript: Boolean): List<String> {
        val ids = entries(catalog).map { it["id"] as String }.toSet()
        val problems = mutableListOf<String>()
        val checked = mutableSetOf<String>()
        catalog["decisions"].arr().map { it.obj() }.forEach { d ->
            val need = d["need"]
            val named = d["modules"].strings() + d["oneOf"].arr().flatMap { it.strings() }
            named.filter { it !in ids }.forEach { problems += "결정표 '$need': 항목에 없는 id '$it'" }
            val flag = d["flag"] as? String
            if (stamped(catalog)) return@forEach
            if (flag != null) {
                val closed = facts.expectedModules(flag)
                (d["modules"].strings().filter { it in facts.modules }.filter { it !in closed }).forEach {
                    problems += "결정표 '$need': 조각 '$flag' 로 찍으면 $it 가 남지 않는다 — 조각에 더한다"
                }
                d["oneOf"].arr().map { it.strings() }.filter { group -> group.none { it in closed } }
                    .forEach { problems += "결정표 '$need': 조각 '$flag' 에 $it 중 하나가 없다" }
                if (runScript && checked.add(flag)) {
                    val run = dryRun(facts.root, flag)
                    if (run.exit != 0) problems += "결정표 '$need': new-project.sh 가 '$flag' 를 받지 않는다 (exit ${run.exit}):\n${run.output.lines().take(6).joinToString("\n") { "    $it" }}"
                }
            } else {
                d["modules"].strings().filter { it in ids }.filter { id -> entries(catalog).first { it["id"] == id }["starter"] != true }
                    .forEach { problems += "결정표 '$need': 스타터에 없는 $it 를 고르는데 flag 가 null 이다" }
            }
        }
        return problems
    }

    fun exampleCommand(ex: Map<String, Any?>): String =
        buildString {
            append("scripts/new-project.sh ${ex["target"]} ${ex["package"]} ${ex["prefix"]} ${ex["classPrefix"]}")
            if (ex["modules"].strings().isNotEmpty()) append(" --modules ${ex["modules"].strings().joinToString(",")}")
            ex["extraFlags"].strings().forEach { append(" $it") }
        }

    fun recipe(catalog: Map<String, Any?>, recipeText: String?, reactRecipeText: String? = null): List<String> {
        val examples = catalog["examples"].arr().map { it.obj() }
        if (examples.isEmpty() && recipeText == null) return emptyList()
        if (recipeText == null) return listOf("docs/new-project-recipe.md 가 없다 — examples 의 명령을 보여 주는 레시피가 있어야 한다")
        val blocks = Regex("""<!-- kotlin-stamp: ([a-z0-9-]+) -->\s*```bash\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL).findAll(recipeText)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        val problems = mutableListOf<String>()
        examples.forEach { ex ->
            val id = ex["id"] as String
            val want = exampleCommand(ex)
            val got = blocks[id]
            if (got == null) problems += "레시피에 '<!-- kotlin-stamp: $id -->' 블록이 없다 — 명령: $want"
            else if (got != want) problems += "레시피 예 '$id' 의 명령이 카탈로그와 다르다.\n    레시피: $got\n    카탈로그: $want"
        }
        if (reactRecipeText != null) {
            val theirs = Regex("""<!-- react-stamp: ([a-z0-9-]+) -->\s*```bash\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL).findAll(reactRecipeText)
                .associate { it.groupValues[1] to it.groupValues[2].trim() }
            examples.forEach { ex ->
                val id = ex["id"] as String
                val want = ex["frontendCommand"] as? String
                val got = theirs[id]
                if (got != null && want != got) problems += "예 '$id' 의 frontendCommand 가 react-skeleton 레시피의 react-stamp 블록과 다르다.\n    여기: $want\n    그쪽: $got"
            }
            val theirKotlin = Regex("""<!-- kotlin-stamp: ([a-z0-9-]+) -->\s*```bash\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL).findAll(reactRecipeText)
                .associate { it.groupValues[1] to it.groupValues[2].trim() }
            examples.forEach { ex ->
                val id = ex["id"] as String
                val got = theirKotlin[id]
                if (got != null && got != exampleCommand(ex)) problems += "예 '$id' 의 kotlin 명령이 react-skeleton 레시피의 kotlin-stamp 블록과 다르다.\n    여기: ${exampleCommand(ex)}\n    그쪽: $got"
            }
        }
        blocks.keys.filter { id -> examples.none { it["id"] == id } }.forEach { problems += "레시피의 kotlin-stamp '$it' 에 해당하는 examples 항목이 없다" }
        if ("react-skeleton" !in recipeText || "new-project-recipe.md" !in recipeText) problems += "레시피에 짝 레포(react-skeleton)의 new-project-recipe.md 로 가는 링크가 없다"
        return problems
    }

    // ---------------------------------------------------------------------------------------------------- 11. 짝 레포

    fun frontend(catalog: Map<String, Any?>, reactRoot: Path?): List<String> {
        if (reactRoot == null) return emptyList()
        val react = Json.parse(reactRoot.resolve("capabilities.json").readText()).obj()
        val reactEntries = react["capabilities"].arr().map { it.obj() }
        val reactIds = reactEntries.map { it["id"] as String }.toSet()
        val problems = mutableListOf<String>()
        entries(catalog).forEach { e ->
            val fe = e["frontend"] as? Map<*, *> ?: return@forEach
            fe["capabilities"].strings().filter { it !in reactIds }.forEach { problems += "${label(e)}: frontend.capabilities 의 '$it' 가 react-skeleton 카탈로그에 없다" }
            fe["packages"].strings().forEach { pkg ->
                val dir = reactRoot.resolve("packages/${pkg.substringAfter('/')}/package.json")
                if (!dir.exists() || "\"name\": \"$pkg\"" !in dir.readText().replace("\"name\":\"", "\"name\": \"")) problems += "${label(e)}: frontend.packages 의 $pkg 가 react-skeleton 에 없다 (packages/${pkg.substringAfter('/')}/package.json)"
            }
            val caps = fe["capabilities"].strings()
            if (caps.size == 1 && caps.first() in reactIds) {
                val want = ((reactEntries.first { it["id"] == caps.first() }["stampFlag"] as? Map<*, *>)?.get("flag")) as? String
                val got = fe["newProjectFlag"] as? String
                if (got != want) problems += "${label(e)}: frontend.newProjectFlag 는 react-skeleton '${caps.first()}' 의 stampFlag.flag 와 같아야 한다 ($want). 지금: $got"
            }
        }
        catalog["decisions"].arr().map { it.obj() }.forEach { d ->
            ((d["frontend"] as? Map<*, *>)?.get("capabilities")).strings().filter { it !in reactIds }
                .forEach { problems += "결정표 '${d["need"]}': frontend.capabilities 의 '$it' 가 react-skeleton 카탈로그에 없다" }
        }
        val siblingUsage = (((catalog["siblings"] as? Map<*, *>)?.get("frontend")) as? Map<*, *>)?.get("usage") as? String
        val reactUsage = (((react["newProject"] as? Map<*, *>)?.get("react")) as? Map<*, *>)?.get("usage") as? String
        if (siblingUsage != null && reactUsage != null && siblingUsage != reactUsage) {
            problems += "siblings.frontend.usage 가 react-skeleton 의 newProject.react.usage 와 다르다.\n    여기: $siblingUsage\n    그쪽: $reactUsage"
        }
        if (stamped(catalog)) return problems   // 찍은 프로젝트는 모듈 일부만 가진다 — 그쪽이 말하는 모든 모듈이 있을 이유가 없다
        val ours = moduleEntries(catalog).map { it["module"] as String }.toSet()
        reactEntries.forEach { r ->
            val b = r["backend"] as? Map<*, *> ?: return@forEach
            (b["modules"].strings() + b["oneOfModules"].arr().flatMap { it.strings() } + b["optionalModules"].strings()).filter { it !in ours }
                .forEach { problems += "react-skeleton '${r["id"]}' 이(가) 말하는 백엔드 모듈 '$it' 이(가) 이 레포의 항목에 없다" }
        }
        return problems
    }

    // ---------------------------------------------------------------------------------------------------- 12. 생성 문서

    fun generatedDocs(root: Path): List<String> {
        if (!root.resolve("scripts/build-capabilities.pl").exists()) return listOf("scripts/build-capabilities.pl 이 없다 — 카탈로그에서 docs/capabilities.md · llms.txt 를 만드는 생성기")
        val p = try {
            ProcessBuilder("perl", "scripts/build-capabilities.pl", "--check").directory(root.toFile()).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            return listOf("perl 을 실행할 수 없다(${e.message}) — new-project.sh 도 perl 이 필요하다")
        }
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return listOf("build-capabilities.pl --check 시간 초과") }
        return if (p.exitValue() == 0) emptyList()
        else listOf("capabilities.json 과 생성물(docs/capabilities.md · llms.txt)이 어긋났다 — perl scripts/build-capabilities.pl 로 다시 만든다:\n$text")
    }
}
