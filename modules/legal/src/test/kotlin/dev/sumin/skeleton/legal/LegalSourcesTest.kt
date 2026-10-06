package dev.sumin.skeleton.legal

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.core.io.DefaultResourceLoader

class LegalSourcesTest {
    private val loader = DefaultResourceLoader()

    @Test
    fun `the manifest of a classpath directory is parsed and the sources are read from it`() {
        val sources = LegalSources("classpath:legal-fixture/", loader)

        val manifest = sources.manifest()!!

        assertEquals(2, manifest.size)
        val first = manifest[0]
        assertEquals("terms", first.type)
        assertEquals("2026-01-01", first.version)
        assertEquals(DocumentStatus.REVIEWED, first.status)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), first.effectiveFrom)
        assertEquals(listOf("ko", "en"), first.locales)
        assertEquals(mapOf("ko" to "a".repeat(64), "en" to "b".repeat(64)), first.sha256)
        assertEquals(false, first.template)
        val second = manifest[1]
        assertEquals(DocumentStatus.DRAFT, second.status)
        assertNull(second.effectiveFrom)
        assertEquals(true, second.template)
        assertTrue(sources.read("terms", "2026-01-01", "ko")!!.startsWith("# 이용약관"))
        assertNull(sources.read("terms", "2026-01-01", "fr"))
    }

    @Test
    fun `a directory without a manifest is reported as absent so the module can fall back to its templates`() {
        assertNull(LegalSources("classpath:no-such-legal-dir/", loader).manifest())
    }

    @Test
    fun `a file location works the same way and a missing trailing slash is tolerated`() {
        val dir = Files.createTempDirectory("legal")
        Files.writeString(dir.resolve("manifest.json"), """{"documents":[{"type":"terms","versions":[{"version":"v1","status":"DRAFT","locales":["ko"]}]}]}""")
        Files.createDirectories(dir.resolve("terms"))
        Files.writeString(dir.resolve("terms/v1.ko.md"), "# T")

        val sources = LegalSources("file:$dir", loader)

        assertEquals("terms", sources.manifest()!!.single().type)
        assertEquals("# T", sources.read("terms", "v1", "ko"))
    }

    @Test
    fun `a broken manifest fails with a message that says what is wrong`() {
        fun parse(json: String) = assertFailsWith<IllegalStateException> { LegalSources.parse(json, "manifest.json") }.message!!

        assertTrue(parse("{not json").startsWith("skeleton.legal: manifest.json is not valid JSON"))
        assertTrue(parse("""{"documents":[{"versions":[]}]}""").contains("'type'"))
        assertTrue(parse("""{"documents":[{"type":"terms","versions":[{"status":"REVIEWED"}]}]}""").contains("terms: version needs 'version'"))
        assertTrue(parse("""{"documents":[{"type":"terms","versions":[{"version":"v1","status":"FINAL","locales":["ko"]}]}]}""").contains("status 'FINAL'"))
        assertTrue(parse("""{"documents":[{"type":"terms","versions":[{"version":"v1","status":"DRAFT","locales":["ko"],"effectiveFrom":"yesterday"}]}]}""").contains("effectiveFrom"))
        assertTrue(parse("""{"other":1}""").contains("'documents'"))
    }

    @Test
    fun `a path segment cannot climb out of the directory`() {
        val sources = LegalSources("classpath:legal-fixture/", loader)
        assertNull(sources.read("../legal-fixture/terms", "2026-01-01", "ko"))
        assertNull(sources.read("terms", "../2026-01-01", "ko"))
        assertNull(sources.read("terms", "2026-01-01", "../ko"))
    }
}
