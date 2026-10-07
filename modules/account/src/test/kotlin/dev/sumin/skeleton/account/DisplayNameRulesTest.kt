package dev.sumin.skeleton.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayNameRulesTest {
    private fun problem(raw: String) = DisplayNameRules.problemOf(DisplayNameRules.clean(raw))

    @Test
    fun `clean trims the ends and folds runs of spaces into one`() {
        assertEquals("Ann B", DisplayNameRules.clean("  Ann \t B ".replace("\t", " ")))
        assertEquals("Ann B", DisplayNameRules.clean("Ann 　 B"), "no-break and ideographic spaces are spaces")
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
        val key = DisplayNameRules.key("Ann")
        assertEquals(key, DisplayNameRules.key("ANN"))
        assertEquals(key, DisplayNameRules.key("Ａｎｎ"))
        assertEquals(DisplayNameRules.key("ｶﾞｲ"), DisplayNameRules.key("ガイ"), "half-width kana")
        assertEquals(DisplayNameRules.key("ǅ"), DisplayNameRules.key("ǆ"))
        assertNotEquals(key, DisplayNameRules.key("Ann B"))
        assertEquals(DisplayNameRules.key("é"), DisplayNameRules.key("é"), "composed and decomposed")
    }

    @Test
    fun `a name whose key would not fit the column is a size problem`() {
        // U+FDFA expands to 18 characters under NFKC
        assertEquals(NameProblem.TOO_LONG, problem("ﷺ".repeat(15)))
    }

    @Test
    fun `sanitize is the lenient path for names a provider or seed hands us`() {
        assertEquals("Ann B", DisplayNameRules.sanitize("  Ann   B "))
        assertEquals("annx", DisplayNameRules.sanitize("ann#@​x"), "unfit characters are dropped, not refused")
        assertEquals("abc", DisplayNameRules.sanitize("deleted:abc"), "never a tombstone look-alike")
        assertEquals("a".repeat(60), DisplayNameRules.sanitize("a".repeat(80)))
        assertNull(DisplayNameRules.sanitize("   "))
        assertNull(DisplayNameRules.sanitize("​#@"))
        assertNull(DisplayNameRules.sanitize(null))
    }

    @Test
    fun `reserved words match on the key without separators`() {
        val reserved = DisplayNameRules.reservedKeys(listOf("admin", "운영자"))
        assertTrue(DisplayNameRules.isReserved("Admin", reserved))
        assertTrue(DisplayNameRules.isReserved("ＡＤＭＩＮ", reserved))
        assertTrue(DisplayNameRules.isReserved("ad min", reserved))
        assertTrue(DisplayNameRules.isReserved("ad_min", reserved))
        assertTrue(DisplayNameRules.isReserved("운영자", reserved))
        assertTrue(!DisplayNameRules.isReserved("administrator", reserved), "only the listed words, not their neighbours")
        assertTrue(!DisplayNameRules.isReserved("Ann", emptySet()))
    }

    @Test
    fun `a generated name is user dash six lowercase hex and never derived from anything else`() {
        val names = (1..50).map { DisplayNames(AccountProperties.DisplayName()).generated() }
        assertTrue(names.all { Regex("^user-[0-9a-f]{6}$").matches(it) }, names.toString())
        assertTrue(names.toSet().size > 40)
        assertNull(problem(names.first()))
    }
}
