package dev.sumin.skeleton.legal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LegalTextTest {
    @Test
    fun `the hash ignores line ending style, a BOM, trailing blanks and trailing newlines`() {
        val a = "# Terms\r\n\r\nHello  \r\nWorld\r\n\r\n"
        val b = "﻿# Terms\n\nHello\nWorld"
        assertEquals(LegalText.sha256(a), LegalText.sha256(b))
        assertEquals(64, LegalText.sha256(a).length)
    }

    @Test
    fun `the hash changes when a single character of the text changes`() {
        assertNotEquals(LegalText.sha256("# Terms\n\nYou may 30 days."), LegalText.sha256("# Terms\n\nYou may 31 days."))
    }

    @Test
    fun `the title is the first level one heading`() {
        assertEquals("이용약관", LegalText.title("intro\n\n# 이용약관\n\n## 1조\n"))
        assertEquals(null, LegalText.title("## only a second level heading"))
    }

    @Test
    fun `placeholders are found and filled, missing ones are listed and left visible`() {
        val source = "{{company-name}} is reachable at {{contact_email}}. {{company-name}} again. {{missing}}"
        assertEquals(setOf("company-name", "contact_email", "missing"), LegalText.placeholders(source))

        val rendered = LegalText.render(source, mapOf("company-name" to "ACME", "contact_email" to "a@b.c"))

        assertEquals("ACME is reachable at a@b.c. ACME again. {{missing}}", rendered.text)
        assertEquals(setOf("missing"), rendered.missing)
    }

    @Test
    fun `a fact value cannot smuggle markup or another placeholder into the text`() {
        val rendered = LegalText.render("Hi {{a}}", mapOf("a" to "<script>x</script> {{b}}"))
        assertTrue(LegalText.problems(rendered.text).isNotEmpty(), "rendered output is still checked by the safe subset")
        assertTrue(rendered.text.contains("{{b}}"), "no second pass: the value is inserted literally")
    }

    @Test
    fun `the safe subset accepts headings, lists, tables, emphasis and https mailto relative and anchor links`() {
        val ok = """
            # Title
            ## Section
            Paragraph with **bold**, *italic*, `code` and a [link](https://example.com/a?b=1), [mail](mailto:a@b.c), [rel](/legal/privacy), [anchor](#top).

            - one
            - two

            1. first
            2. second

            > quote

            | a | b |
            |---|---|
            | 1 | 2 |

            ---
        """.trimIndent()
        assertEquals(emptyList(), LegalText.problems(ok))
    }

    @Test
    fun `raw html, scripts, images and unsafe link destinations are problems`() {
        assertTrue(LegalText.problems("<b>x</b>").any { it.contains("HTML") })
        assertTrue(LegalText.problems("<script>alert(1)</script>").isNotEmpty())
        assertTrue(LegalText.problems("![img](https://x/y.png)").any { it.contains("image") })
        assertTrue(LegalText.problems("[x](javascript:alert(1))").any { it.contains("javascript") })
        assertTrue(LegalText.problems("[x](JaVaScRiPt:alert(1))").isNotEmpty())
        assertTrue(LegalText.problems("[x](data:text/html;base64,AAAA)").isNotEmpty())
        assertTrue(LegalText.problems("[x](//evil.example/x)").isNotEmpty(), "scheme-relative links are not relative links")
        assertTrue(LegalText.problems("[x](ftp://example.com)").isNotEmpty())
    }

    @Test
    fun `a problem names the line so the author can find it`() {
        val problems = LegalText.problems("fine\n\n<div>bad</div>\n")
        assertTrue(problems.single().startsWith("line 3"), problems.toString())
    }
}
