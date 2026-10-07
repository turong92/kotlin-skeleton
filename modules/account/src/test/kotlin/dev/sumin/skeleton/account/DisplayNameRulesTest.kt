package dev.sumin.skeleton.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayNameRulesTest {
    private fun problem(raw: String) = DefaultDisplayNameRules.problemOf(DefaultDisplayNameRules.clean(raw))

    @Test
    fun `clean trims the ends and folds runs of spaces into one`() {
        assertEquals("Ann B", DefaultDisplayNameRules.clean("  Ann \t B ".replace("\t", " ")))
        assertEquals("Ann B", DefaultDisplayNameRules.clean("Ann 　 B"), "no-break and ideographic spaces are spaces")
    }

    @Test
    fun `a plain name is fine at 1 and at 60 code points and not at 61`() {
        assertNull(problem("A"))
        assertNull(problem("a".repeat(60)))
        assertEquals(NameProblem.TOO_LONG, problem("a".repeat(61)))
        assertNull(problem("😀".repeat(60)), "length counts code points, not UTF-16 units")
        assertEquals(NameProblem.TOO_LONG, problem("😀".repeat(61)))
    }

    @Test
    fun `an empty name is a problem of its own`() {
        assertEquals(NameProblem.EMPTY, problem("   "))
        assertEquals(NameProblem.EMPTY, problem(""))
    }

    @Test
    fun `control characters line breaks and invisible characters are refused`() {
        listOf("a\nb", "a\tb", "a\u0000b", "a​b", "a‍b", "‮evil", "a﻿b", "a b", "abㅤ", "⠀⠀", "a­b").forEach {
            assertEquals(NameProblem.INVALID_CHARACTERS, problem(it), "should refuse ${it.map { c -> "U+%04X".format(c.code) }}")
        }
    }

    @Test
    fun `a hash sign and an at sign are refused - they look like a tag or a mention`() {
        assertEquals(NameProblem.INVALID_CHARACTERS, problem("ann#0001"))
        assertEquals(NameProblem.INVALID_CHARACTERS, problem("@ann"))
        assertEquals(NameProblem.INVALID_CHARACTERS, problem("ann＠x"), "a full-width at sign is the same sign after NFKC")
    }

    @Test
    fun `the tombstone prefix is refused in any case and in full-width form`() {
        assertEquals(NameProblem.INVALID_CHARACTERS, problem("deleted:abc"))
        assertEquals(NameProblem.INVALID_CHARACTERS, problem("Deleted:abc"))
        assertEquals(NameProblem.INVALID_CHARACTERS, problem("ｄｅｌｅｔｅｄ：abc"))
        assertNull(problem("deleted items"), "only the prefix with the colon collides with a tombstone")
    }

    @Test
    fun `a name needs at least one visible character - marks alone are not a name`() {
        assertEquals(NameProblem.INVALID_CHARACTERS, problem("́́"))
        assertNull(problem("김수민"))
        assertNull(problem("🎤"))
    }

    @Test
    fun `the key folds width compatibility forms and case so look-alikes collide`() {
        val key = DefaultDisplayNameRules.key("Ann")
        assertEquals(key, DefaultDisplayNameRules.key("ANN"))
        assertEquals(key, DefaultDisplayNameRules.key("Ａｎｎ"))
        assertEquals(DefaultDisplayNameRules.key("ｶﾞｲ"), DefaultDisplayNameRules.key("ガイ"), "half-width kana")
        assertEquals(DefaultDisplayNameRules.key("ǅ"), DefaultDisplayNameRules.key("ǆ"))
        assertNotEquals(key, DefaultDisplayNameRules.key("Ann B"))
        assertEquals(DefaultDisplayNameRules.key("é"), DefaultDisplayNameRules.key("é"), "composed and decomposed")
    }

    @Test
    fun `a name whose key would not fit the column is a size problem`() {
        // U+FDFA expands to 18 characters under NFKC
        assertEquals(NameProblem.TOO_LONG, problem("ﷺ".repeat(15)))
    }

    @Test
    fun `sanitize is the lenient path for names a provider or seed hands us`() {
        assertEquals("Ann B", DefaultDisplayNameRules.sanitize("  Ann   B "))
        assertEquals("annx", DefaultDisplayNameRules.sanitize("ann#@​x"), "unfit characters are dropped, not refused")
        assertEquals("abc", DefaultDisplayNameRules.sanitize("deleted:abc"), "never a tombstone look-alike")
        assertEquals("a".repeat(60), DefaultDisplayNameRules.sanitize("a".repeat(80)))
        assertNull(DefaultDisplayNameRules.sanitize("   "))
        assertNull(DefaultDisplayNameRules.sanitize("​#@"))
        assertNull(DefaultDisplayNameRules.sanitize(null))
    }

    @Test
    fun `reserved words match on the key without separators`() {
        val reserved = DefaultDisplayNameRules.reservedKeys(listOf("admin", "운영자"))
        assertTrue(DefaultDisplayNameRules.isReserved("Admin", reserved))
        assertTrue(DefaultDisplayNameRules.isReserved("ＡＤＭＩＮ", reserved))
        assertTrue(DefaultDisplayNameRules.isReserved("ad min", reserved))
        assertTrue(DefaultDisplayNameRules.isReserved("ad_min", reserved))
        assertTrue(DefaultDisplayNameRules.isReserved("운영자", reserved))
        assertTrue(!DefaultDisplayNameRules.isReserved("administrator", reserved), "only the listed words, not their neighbours")
        assertTrue(!DefaultDisplayNameRules.isReserved("Ann", emptySet()))
    }

    @Test
    fun `a generated name is user dash six lowercase hex and never derived from anything else`() {
        val names = (1..50).map { DisplayNames(AccountProperties.DisplayName()).generated() }
        assertTrue(names.all { Regex("^user-[0-9a-f]{6}$").matches(it) }, names.toString())
        assertTrue(names.toSet().size > 40)
        assertNull(problem(names.first()))
    }

    // ---- invisible characters: the key ignores them, the input rule lets in only the ones with a job (emoji sequences)

    @Test
    fun `a ZWJ emoji sequence and an emoji with a variation selector are fine and stay as typed`() {
        assertNull(problem("👩‍💻"), "woman technologist")
        assertNull(problem("👩‍💻 Ann"))
        assertNull(problem("❤️"), "heart + VS16")
        assertNull(problem("❤️‍🔥"), "heart on fire: VS16 then ZWJ")
        assertNull(problem("1️⃣"), "keycap")
        assertEquals("👩‍💻", DefaultDisplayNameRules.clean("👩‍💻"), "the display string keeps the joiner")
    }

    @Test
    fun `a joiner or selector that is not part of such a sequence is refused`() {
        listOf("a\u200Db", "👩\u200D", "\u200D👩", "👩\u200D\u200D💻", "\uFE0F", "수민\uFE0F\uFE0F", "a\u034Fb", "a\u180Bb", "a\uDB40\uDC01b", "\uDB40\uDD00수").forEach {
            assertEquals(NameProblem.INVALID_CHARACTERS, problem(it), "should refuse ${it.map { c -> "U+%04X".format(c.code) }}")
        }
        assertNull(problem("葛\uDB40\uDD00"), "an ideographic variation selector after a kanji")
    }

    @Test
    fun `the key ignores every default-ignorable code point so names that look the same are the same`() {
        assertEquals(DefaultDisplayNameRules.key("수민"), DefaultDisplayNameRules.key("수민\uFE0F"))
        assertEquals(DefaultDisplayNameRules.key("👩💻"), DefaultDisplayNameRules.key("👩‍💻"))
        assertEquals(DefaultDisplayNameRules.key("é"), DefaultDisplayNameRules.key("e\u034F\u0301"), "a CGJ cannot split a letter from its accent")
        assertEquals(DefaultDisplayNameRules.key("a"), DefaultDisplayNameRules.key("a\u200B\u2060\u00AD\uDB40\uDD00"))
        assertEquals(NameProblem.INVALID_CHARACTERS, problem("d\uFE0Feleted:x"), "the tombstone prefix is judged on the key")
    }

    @Test
    fun `sanitize keeps a joiner that belongs to an emoji and drops the invisible characters that do not`() {
        assertEquals("👩‍💻 Ann", DefaultDisplayNameRules.sanitize("👩‍💻 Ann"))
        assertEquals("ab", DefaultDisplayNameRules.sanitize("a\u200Db"))
        assertEquals("ab", DefaultDisplayNameRules.sanitize("a\u034Fb"))
        assertEquals("수민️", DefaultDisplayNameRules.sanitize("수민\uFE0F"))
        assertEquals("ab", DefaultDisplayNameRules.sanitize("a\uFE0F\uFE0Fb".replace("\uFE0F\uFE0F", "\u2060")))
    }
}
