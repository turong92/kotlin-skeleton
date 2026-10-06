package dev.sumin.skeleton.account.mail

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 메일 12종 × ko · en 의 제목 · 텍스트 · HTML 을 골든 파일(`src/test/resources/mail-golden`)과 바이트 단위로 맞춘다.
 * 문구 · 틀을 일부러 바꿨다면 `UPDATE_GOLDEN=1 ./gradlew :modules:account:test --tests '*AccountMailGoldenTest'` 로 다시 쓰고 diff 를 눈으로 본다.
 */
class AccountMailGoldenTest {
    private val dir: Path = Path.of(System.getProperty("user.dir")).resolve("src/test/resources/mail-golden")
    private val update = System.getenv("UPDATE_GOLDEN") == "1"
    private val templates = DefaultAccountMailTemplates(MailFixtures.props)

    private fun check(name: String, actual: String) {
        val file = dir.resolve(name)
        if (update) { Files.createDirectories(dir); Files.writeString(file, actual) }
        assertTrue(Files.exists(file), "missing golden file $name — run with UPDATE_GOLDEN=1")
        assertEquals(Files.readString(file), actual, "golden mismatch: $name")
    }

    @Test
    fun `every mail kind in ko and en matches its golden files`() {
        for (kind in MailKind.entries) for (lang in MailFixtures.langs) {
            val r = templates.render(kind, lang, MailFixtures.vars, MailFixtures.link(kind))
            val base = "${kind.name.lowercase()}.$lang"
            check("$base.txt", "subject: ${r.subject}\n\n${r.text}\n")
            check("$base.html", r.html!! + "\n")
        }
    }

    @Test
    fun `the golden directory holds exactly the files of the current kinds`() {
        if (update) return
        val expected = MailKind.entries.flatMap { k -> MailFixtures.langs.flatMap { l -> listOf("${k.name.lowercase()}.$l.txt", "${k.name.lowercase()}.$l.html") } }.toSet()
        assertEquals(expected, Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
    }
}
