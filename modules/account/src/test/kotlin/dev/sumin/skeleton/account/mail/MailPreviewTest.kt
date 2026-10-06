package dev.sumin.skeleton.account.mail

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** `./gradlew :modules:account:mailPreview` — 메일마다 `build/mail-preview/<kind>.<lang>.html` 을 쓴다 (브라우저로 열어 눈으로 본다). `index.html` 이 목록이다 */
class MailPreviewTest {
    @Test
    fun `writes one html file per kind and language plus an index`() {
        val out = Path.of(System.getProperty("user.dir")).resolve("build/mail-preview")
        Files.createDirectories(out)
        val templates = DefaultAccountMailTemplates(MailFixtures.props)
        val rows = StringBuilder()
        for (kind in MailKind.entries) for (lang in MailFixtures.langs) {
            val r = templates.render(kind, lang, MailFixtures.vars, MailFixtures.link(kind))
            val name = "${kind.name.lowercase()}.$lang.html"
            Files.writeString(out.resolve(name), r.html!!)
            rows.append("<li><a href=\"").append(name).append("\">").append(name).append("</a> — ").append(r.subject.replace("<", "&lt;")).append("</li>\n")
        }
        Files.writeString(out.resolve("index.html"), "<!doctype html><meta charset=\"utf-8\"><title>mail preview</title><ul>\n$rows</ul>")
        assertTrue(Files.exists(out.resolve("verify_code.ko.html")))
    }
}
