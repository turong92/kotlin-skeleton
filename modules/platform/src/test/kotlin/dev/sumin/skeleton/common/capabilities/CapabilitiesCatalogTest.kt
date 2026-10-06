package dev.sumin.skeleton.common.capabilities

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `capabilities.json` — 새 프로젝트를 시킬 때 에이전트가 「이미 무엇이 준비됐는가」를 찾는 정본 — 이 레포의 실제와 맞는지 본다.
 * 가드 하나 = 테스트 하나(실패하면 무엇을 붙이면 되는지까지 말한다). 가드가 정말 무는지는 CapabilitiesGuardsTest.
 *
 * 찍은 프로젝트(모듈 일부)에서도 통과해야 한다 — 찍을 때 카탈로그도 고른 모듈로 걸러 다시 쓴다(scripts/new-project.d/stamp-capabilities.pl).
 */
class CapabilitiesCatalogTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })
    private val facts = RepoFacts(root)
    private val catalog: Map<String, Any?> by lazy {
        val file = root.resolve("capabilities.json")
        assertTrue(file.exists(), "capabilities.json 이 없다 — 레포 루트에 카탈로그가 있어야 한다(docs/capabilities.schema.json 참고)")
        Json.parse(file.readText()).obj()
    }
    private val scriptPresent = root.resolve("scripts/new-project.sh").exists()
    private val react: Path? = root.parent?.resolve("react-skeleton")?.takeIf { it.resolve("capabilities.json").exists() }

    private fun assertNone(problems: List<String>) = assertEquals(emptyList(), problems, "\n" + problems.joinToString("\n\n"))

    @Test
    fun `every module and app has an entry and every entry has its module or app`() = assertNone(CapabilitiesGuards.coverage(catalog, facts))

    @Test
    fun `the catalog follows its schema`() =
        assertNone(CapabilitiesGuards.schema(catalog, Json.parse(root.resolve("docs/capabilities.schema.json").readText()).obj()))

    @Test
    fun `ids are unique and entries agree with each other and with the starter`() = assertNone(CapabilitiesGuards.entries(catalog, facts))

    @Test
    fun `new project flags are accepted and autoIncludes equal the real dependency closure`() =
        assertNone(CapabilitiesGuards.stampFlags(catalog, facts, runScript = scriptPresent))

    @Test
    fun `config prefixes match the ConfigurationProperties in the code`() = assertNone(CapabilitiesGuards.config(catalog, facts))

    @Test
    fun `base paths match the controller mappings`() = assertNone(CapabilitiesGuards.basePaths(catalog, facts))

    @Test
    fun `docs paths exist`() = assertNone(CapabilitiesGuards.docs(catalog, facts))

    @Test
    fun `secrets match the per-module table in docs deploy`() = assertNone(CapabilitiesGuards.secrets(catalog, facts))

    @Test
    fun `every entry has Korean and English keywords and no placeholder is left`() = assertNone(CapabilitiesGuards.keywords(catalog))

    @Test
    fun `decisions name real entries and flags new-project accepts`() = assertNone(CapabilitiesGuards.decisions(catalog, facts, runScript = scriptPresent))

    @Test
    fun `the recipe commands are the examples commands`() {
        val recipe = root.resolve("docs/new-project-recipe.md").takeIf { it.exists() }?.readText()
        val reactRecipe = react?.resolve("docs/new-project-recipe.md")?.takeIf { it.exists() }?.readText()
        assertNone(CapabilitiesGuards.recipe(catalog, recipe, reactRecipe))
    }

    @Test
    fun `frontend packages and capabilities exist in the sibling react repo when it is checked out`() =
        assertNone(CapabilitiesGuards.frontend(catalog, react))

    @Test
    fun `generated docs are current`() = assertNone(CapabilitiesGuards.generatedDocs(root))
}
