package dev.sumin.skeleton.common.capabilities

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 가드가 정말 무는지 본다 — 일부러 틀린 카탈로그를 먹여 가드가 그 틀림을 말하는지.
 * (진짜 카탈로그가 맞는지는 CapabilitiesCatalogTest. 이 시험은 카탈로그와 무관하게 레포의 모듈 · 문서만 쓴다.)
 */
class CapabilitiesGuardsTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })
    private val facts = RepoFacts(root)

    private fun entry(id: String, vararg over: Pair<String, Any?>): MutableMap<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "id" to id, "kind" to "module", "module" to id, "path" to "modules/$id", "status" to "stable",
            "summary" to "시험용 항목 — 한 문장 요약이다.", "starter" to false, "newProjectFlag" to "--modules $id",
            "autoIncludes" to emptyList<String>(),
            "needs" to linkedMapOf("requires" to emptyList<String>(), "oneOf" to emptyList<List<String>>(), "optional" to emptyList<String>()),
            "config" to null, "basePaths" to emptyList<String>(), "secrets" to emptyList<Any?>(),
            "docs" to listOf("docs/modules/$id.md"), "frontend" to null, "notFor" to listOf("시험용 — 쓰지 않는 경우"),
            "keywords" to linkedMapOf("ko" to listOf("시험"), "en" to listOf("test")),
        )
        over.forEach { base[it.first] = it.second }
        return base
    }

    private fun catalog(vararg entries: Map<String, Any?>, decisions: List<Any?> = emptyList(), examples: List<Any?> = emptyList()): Map<String, Any?> =
        linkedMapOf(
            "schemaVersion" to 1, "mode" to "skeleton", "name" to "t", "summary" to "시험용 카탈로그 요약이다.",
            "starterModules" to facts.starterClosure.sorted(), "guides" to emptyList<Any?>(),
            "capabilities" to entries.toList(), "decisions" to decisions, "examples" to examples,
        )

    private fun problemsText(list: List<String>) = list.joinToString("\n")

    // --- 1. 모듈 · 앱 ↔ 항목 --------------------------------------------------------------------------------------

    @Test
    fun `a module without an entry fails and prints the object to add`() {
        val text = problemsText(CapabilitiesGuards.coverage(catalog(), facts))
        assertTrue("modules/board" in text, text.take(400))
        // 붙여 넣을 객체: 계산 가능한 칸은 이미 채워져 있다
        assertTrue("\"id\" : \"board\"" in text || "\"id\": \"board\"" in text, "id 가 있는 객체를 보여 준다")
        assertTrue("\"autoIncludes\"" in text && "\"idempotency\"" in text && "\"notification\"" in text, "board 가 컴파일 전용으로 끌고 오는 모듈이 계산되어 있다")
        assertTrue("\"basePaths\"" in text && "/api/v1/boards" in text, "컨트롤러에서 뽑은 경로")
        assertTrue("skeleton.board" in text, "접두사")
    }

    @Test
    fun `an app without an entry fails too`() {
        val text = problemsText(CapabilitiesGuards.coverage(catalog(), facts))
        assertTrue("apps/sample" in text && "apps/workbench" in text, text.take(300))
    }

    @Test
    fun `an entry whose module does not exist fails`() {
        val text = problemsText(CapabilitiesGuards.coverage(catalog(entry("ghost")), facts))
        assertTrue("ghost" in text && "modules/ghost" in text, text.take(300))
    }

    // --- 2. 스키마 ---------------------------------------------------------------------------------------------------

    private val schema: Map<String, Any?> by lazy { Json.parse(root.resolve("docs/capabilities.schema.json").readText()).obj() }

    @Test
    fun `an entry missing a required field fails the schema and names the field`() {
        val e = entry("board").also { it.remove("keywords") }
        val text = problemsText(CapabilitiesGuards.schema(catalog(e), schema))
        assertTrue("keywords" in text, text)
    }

    @Test
    fun `a value outside the enum fails the schema`() {
        val text = problemsText(CapabilitiesGuards.schema(catalog(entry("board", "kind" to "plugin")), schema))
        assertTrue("kind" in text && "plugin" in text, text)
    }

    // --- 3. 조각 · autoIncludes ----------------------------------------------------------------------------------

    @Test
    fun `wrong autoIncludes fails and prints what the closure really is`() {
        val e = entry("board", "newProjectFlag" to "--modules board,board-jdbc", "autoIncludes" to listOf("crypto"))
        val text = problemsText(CapabilitiesGuards.stampFlags(catalog(e), facts, runScript = false))
        assertTrue("board" in text && "notification" in text, text)   // idempotency 는 이제 스타터(account 의 선택 통합)라 따라오는 모듈이 아니다
    }

    @Test
    fun `a module name new-project would not accept fails`() {
        val e = entry("board", "newProjectFlag" to "--modules board,nope")
        val text = problemsText(CapabilitiesGuards.stampFlags(catalog(e), facts, runScript = true))
        assertTrue("nope" in text, text)
    }

    @Test
    fun `new-project dry-run lists the modules and writes nothing`() {
        val before = Files.list(root).use { it.count() }
        val run = CapabilitiesGuards.dryRun(root, "--modules board,board-jdbc")
        assertEquals(0, run.exit, run.output)
        assertTrue(setOf("board", "board-jdbc", "notification", "idempotency", "platform", "auth").all { it in run.modules }, run.output)
        assertEquals(before, Files.list(root).use { it.count() }, "dry-run 은 레포에 아무것도 쓰지 않는다")
    }

    @Test
    fun `a starter module with a flag fails`() {
        val e = entry("auth", "starter" to true, "newProjectFlag" to "--modules auth")
        val text = problemsText(CapabilitiesGuards.stampFlags(catalog(e), facts, runScript = false))
        assertTrue("auth" in text && "starter" in text, text)
    }

    // --- 4. 설정 접두사 ----------------------------------------------------------------------------------------------

    @Test
    fun `a config prefix the code does not declare fails and lists the real ones`() {
        val e = entry("board", "config" to linkedMapOf("prefixes" to listOf("skeleton.boards"), "snippet" to "docs/config/modules/board.yml"))
        val text = problemsText(CapabilitiesGuards.config(catalog(e), facts))
        assertTrue("skeleton.boards" in text && "skeleton.board" in text, text)
    }

    @Test
    fun `a module with a prefix but no config block fails`() {
        val text = problemsText(CapabilitiesGuards.config(catalog(entry("board")), facts))
        assertTrue("board" in text && "skeleton.board" in text, text)
    }

    // --- 5. HTTP 경로 ------------------------------------------------------------------------------------------------

    @Test
    fun `base paths that differ from the controllers fail and print the real ones`() {
        val text = problemsText(CapabilitiesGuards.basePaths(catalog(entry("board", "basePaths" to listOf("/api/v1/board"))), facts))
        assertTrue("/api/v1/board" in text && "/api/v1/boards" in text, text)
    }

    @Test
    fun `a module that opens a path but lists none fails`() {
        val text = problemsText(CapabilitiesGuards.basePaths(catalog(entry("storage")), facts))
        assertTrue("/api/v1/storage" in text, text)
    }

    // --- 6. 문서 경로 ------------------------------------------------------------------------------------------------

    @Test
    fun `a docs path that does not exist fails`() {
        val e = entry("board", "docs" to listOf("docs/modules/board.md", "docs/nope.md"))
        val text = problemsText(CapabilitiesGuards.docs(catalog(e), facts))
        assertTrue("docs/nope.md" in text, text)
    }

    @Test
    fun `a module entry without its module page fails`() {
        val text = problemsText(CapabilitiesGuards.docs(catalog(entry("board", "docs" to listOf("docs/sample.md"))), facts))
        assertTrue("docs/modules/board.md" in text, text)
    }


    @Test
    fun `a module page and its entry that disagree about the frontend package fail`() {
        val text = problemsText(CapabilitiesGuards.docs(catalog(entry("board", "frontend" to null)), facts))
        assertTrue("프론트 짝" in text && "@skeleton/board" in text, text)
    }

    // --- 7. 비밀 ↔ docs/deploy.md ------------------------------------------------------------------------------------

    @Test
    fun `a module in the deploy secrets table without secrets in its entry fails`() {
        val text = problemsText(CapabilitiesGuards.secrets(catalog(entry("payment-toss")), facts))
        assertTrue("payment-toss" in text, text)
    }

    @Test
    fun `secrets on a module the deploy table does not list fail`() {
        val e = entry("board", "secrets" to listOf(linkedMapOf("env" to listOf("<P>_X"), "whenMissing" to "아무 일도 없다")))
        val text = problemsText(CapabilitiesGuards.secrets(catalog(e), facts))
        assertTrue("board" in text, text)
    }

    // --- 8. 키워드 ---------------------------------------------------------------------------------------------------

    @Test
    fun `empty keyword lists and placeholders fail`() {
        val e = entry("board", "keywords" to linkedMapOf("ko" to emptyList<String>(), "en" to listOf("board")))
        assertTrue("board" in problemsText(CapabilitiesGuards.keywords(catalog(e))) && "ko" in problemsText(CapabilitiesGuards.keywords(catalog(e))))
        val todo = entry("board", "summary" to "TODO")
        assertTrue("TODO" in problemsText(CapabilitiesGuards.keywords(catalog(todo))))
    }

    // --- 9. 항목끼리의 참조 · 결정표 · 예 -------------------------------------------------------------------------------

    @Test
    fun `an id that is not unique or a need that points nowhere fails`() {
        val dup = problemsText(CapabilitiesGuards.entries(catalog(entry("board"), entry("board")), facts))
        assertTrue("board" in dup && "중복" in dup, dup)
        val bad = entry("board", "needs" to linkedMapOf("requires" to listOf("board-jdbcx"), "oneOf" to emptyList<List<String>>(), "optional" to emptyList<String>()))
        assertTrue("board-jdbcx" in problemsText(CapabilitiesGuards.entries(catalog(bad), facts)))
    }

    @Test
    fun `a decision naming an unknown entry or a flag new-project rejects fails`() {
        val d = linkedMapOf<String, Any?>(
            "need" to "게시판", "modules" to listOf("board", "nowhere"), "oneOf" to emptyList<Any?>(),
            "flag" to "--modules board,nowhere", "frontend" to null, "byHand" to "손으로 쓸 것이다.",
        )
        val text = problemsText(CapabilitiesGuards.decisions(catalog(entry("board"), decisions = listOf(d)), facts, runScript = true))
        assertTrue("nowhere" in text, text)
    }

    @Test
    fun `a recipe command that differs from the example fails`() {
        val ex = linkedMapOf<String, Any?>(
            "id" to "community", "title" to "t", "product" to "p", "modules" to listOf("board", "board-jdbc"), "extraFlags" to emptyList<String>(),
            "target" to "~/work/community/api", "package" to "dev.example.community", "prefix" to "community", "classPrefix" to "Community",
            "frontendCapabilities" to emptyList<String>(), "frontendCommand" to "scripts/new-project.sh ~/work/community/web community --packages board",
        )
        val recipe = "<!-- kotlin-stamp: community -->\n\n```bash\nscripts/new-project.sh ~/work/community/api dev.example.community community Community --modules board\n```\n"
        val text = problemsText(CapabilitiesGuards.recipe(catalog(examples = listOf(ex)), recipe))
        assertTrue("community" in text && "--modules board,board-jdbc" in text, text)
        assertTrue("community" in problemsText(CapabilitiesGuards.recipe(catalog(examples = listOf(ex)), "(레시피에 블록이 없다)")))
    }


    @Test
    fun `a react command that differs from the sibling recipe fails`() {
        val ex = linkedMapOf<String, Any?>(
            "id" to "community", "title" to "t", "product" to "p", "modules" to listOf("board", "board-jdbc"), "extraFlags" to emptyList<String>(),
            "target" to "~/work/community/api", "package" to "dev.example.community", "prefix" to "community", "classPrefix" to "Community",
            "frontendCapabilities" to emptyList<String>(), "frontendCommand" to "scripts/new-project.sh ~/work/community/web community --packages board",
        )
        val own = "<!-- kotlin-stamp: community -->\n\n```bash\n${CapabilitiesGuards.exampleCommand(ex)}\n```\n 짝: react-skeleton new-project-recipe.md"
        val theirs = "<!-- react-stamp: community -->\n\n```bash\nscripts/new-project.sh ~/work/community/web community --packages board,notifications\n```\n"
        val text = problemsText(CapabilitiesGuards.recipe(catalog(examples = listOf(ex)), own, theirs))
        assertTrue("community" in text && "--packages board,notifications" in text, text)
        assertEquals(emptyList(), CapabilitiesGuards.recipe(catalog(examples = listOf(ex)), own, null))
    }

    @Test
    fun `a kotlin command in the sibling recipe that differs from the example fails`() {
        val ex = linkedMapOf<String, Any?>(
            "id" to "community", "title" to "t", "product" to "p", "modules" to listOf("board", "board-jdbc"), "extraFlags" to emptyList<String>(),
            "target" to "~/work/community/api", "package" to "dev.example.community", "prefix" to "community", "classPrefix" to "Community",
            "frontendCapabilities" to emptyList<String>(), "frontendCommand" to "scripts/new-project.sh ~/work/community/web community --packages board",
        )
        val own = "<!-- kotlin-stamp: community -->\n\n```bash\n${CapabilitiesGuards.exampleCommand(ex)}\n```\n 짝: react-skeleton new-project-recipe.md"
        val theirs = "<!-- react-stamp: community -->\n\n```bash\n${ex["frontendCommand"]}\n```\n<!-- kotlin-stamp: community -->\n\n```bash\nscripts/new-project.sh ~/work/community/api dev.example.community community Community --modules board\n```\n"
        val text = problemsText(CapabilitiesGuards.recipe(catalog(examples = listOf(ex)), own, theirs))
        assertTrue("community" in text && "react-skeleton 레시피의 kotlin-stamp" in text, text)
    }

    // --- 10. 짝 레포 ---------------------------------------------------------------------------------------------------

    private fun fakeReact(): Path {
        val r = Files.createTempDirectory("fake-react")
        Files.createDirectories(r.resolve("packages/board"))
        r.resolve("packages/board/package.json").writeText("""{"name":"@skeleton/board"}""")
        r.resolve("capabilities.json").writeText(
            """{"capabilities":[{"id":"board","package":"@skeleton/board","stampFlag":{"flag":"--packages board"},"backend":{"modules":["board","board-jdbc"],"oneOfModules":[],"optionalModules":[]}}]}""",
        )
        return r
    }

    @Test
    fun `a frontend package or capability the sibling repo lacks fails`() {
        val react = fakeReact()
        val e = entry("board", "frontend" to linkedMapOf("capabilities" to listOf("forum"), "packages" to listOf("@skeleton/forum"), "newProjectFlag" to "--packages forum"))
        val text = problemsText(CapabilitiesGuards.frontend(catalog(e), react))
        assertTrue("@skeleton/forum" in text && "forum" in text, text)
    }

    @Test
    fun `a sibling backend module this repo does not have fails`() {
        val react = fakeReact()
        react.resolve("capabilities.json").writeText(
            """{"capabilities":[{"id":"board","package":"@skeleton/board","backend":{"modules":["board","board-nowhere"],"oneOfModules":[],"optionalModules":[]}}]}""",
        )
        val text = problemsText(CapabilitiesGuards.frontend(catalog(entry("board")), react))
        assertTrue("board-nowhere" in text, text)
    }

    @Test
    fun `without the sibling checkout the frontend guard stays silent`() {
        assertEquals(emptyList(), CapabilitiesGuards.frontend(catalog(entry("board")), null))
    }


    @Test
    fun `a frontend usage line that differs from the sibling catalog fails`() {
        val react = fakeReact()
        react.resolve("capabilities.json").writeText(
            """{"newProject":{"react":{"usage":"scripts/new-project.sh <dir> <name> [--packages a]"}},"capabilities":[]}""",
        )
        val cat = catalog(entry("board")).toMutableMap()
        cat["siblings"] = linkedMapOf("frontend" to linkedMapOf("repo" to "react-skeleton", "catalog" to "capabilities.json", "recipe" to "r.md", "usage" to "scripts/new-project.sh <dir> <name>"))
        val text = problemsText(CapabilitiesGuards.frontend(cat, react))
        assertTrue("usage" in text && "[--packages a]" in text, text)
    }

    @Test
    fun `a stamped catalog is not held to the skeleton's new-project flags`() {
        val cat = catalog(entry("board", "newProjectFlag" to null, "autoIncludes" to emptyList<String>())).toMutableMap()
        cat["mode"] = "stamped"
        assertEquals(emptyList(), CapabilitiesGuards.stampFlags(cat, facts, runScript = false))
    }

    // --- 11. 생성 문서 ------------------------------------------------------------------------------------------------

    @Test
    fun `generated docs that no longer match capabilities json fail`() {
        val tmp = Files.createTempDirectory("caps-gen")
        listOf("capabilities.json", "docs/capabilities.md", "llms.txt", "scripts/build-capabilities.pl", "docs/capabilities.schema.json").forEach { rel ->
            val from = root.resolve(rel)
            if (from.exists()) {
                Files.createDirectories(tmp.resolve(rel).parent)
                Files.copy(from, tmp.resolve(rel))
            }
        }
        assertEquals(emptyList(), CapabilitiesGuards.generatedDocs(tmp), "고치기 전에는 맞다")
        tmp.resolve("capabilities.json").let { it.writeText(it.readText().replaceFirst("\"summary\": \"", "\"summary\": \"(고침) ")) }
        val text = problemsText(CapabilitiesGuards.generatedDocs(tmp))
        assertTrue("capabilities" in text && "perl scripts/build-capabilities.pl" in text, text)
    }
}
