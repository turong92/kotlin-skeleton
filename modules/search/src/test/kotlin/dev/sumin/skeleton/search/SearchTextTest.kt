package dev.sumin.skeleton.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTextTest {
    // ---- 정규화 ----

    @Test
    fun `normalize folds full-width and compatibility forms and drops control characters`() {
        assertEquals("ABC123 ガ", SearchText.normalize("ＡＢＣ１２３ ｶﾞ"))
        assertEquals("a b", SearchText.normalize("a\u0000b"))
        assertEquals("a b\nc", SearchText.normalize("a\u0007b\nc"))
    }

    // ---- 2글자 조각 ----

    @Test
    fun `grams are overlapping two-character pieces per run with a break marker between runs`() {
        assertEquals("가방 방끈 ${SearchText.BREAK}", SearchText.grams("가방끈"))
        assertEquals("ab bc ${SearchText.BREAK} 한글 ${SearchText.BREAK}", SearchText.grams("Abc, 한글"))
    }

    @Test
    fun `a one-character run yields no pieces but still separates its neighbours`() {
        // 'ab c bc' 에서 ab 와 bc 가 조각열에서 이어지면 질의 'abc' 가 두 낱말에 맞아 버린다
        assertEquals("ab ${SearchText.BREAK} ${SearchText.BREAK} bc ${SearchText.BREAK}", SearchText.grams("ab c bc"))
    }

    @Test
    fun `the break marker can never equal a piece`() {
        assertTrue(SearchText.BREAK.length >= 3)
        assertTrue(SearchText.BREAK.all { it in 'a'..'z' })
    }

    @Test
    fun `runs are letters and digits of any script and split on punctuation and emoji`() {
        assertEquals(listOf("e", "mail", "12", "가방끈"), SearchText.runs("E-mail 12! 가방끈😀"))
    }

    // ---- 질의어 ----

    @Test
    fun `query phrases drop syntax characters and runs shorter than two characters`() {
        assertEquals(listOf("가방끈", "방끈", "mail"), SearchText.queryPhrases("  \"가방끈\" +방끈 * ' a e-mail 한"))
    }

    @Test
    fun `query phrases are normalized folded distinct and capped at five`() {
        assertEquals(listOf("ab"), SearchText.queryPhrases("ＡＢ ab Ab"))
        assertEquals(listOf("aa", "bb", "cc", "dd", "ee"), SearchText.queryPhrases("aa bb cc dd ee ff gg"))
    }

    @Test
    fun `a query with nothing searchable has no phrases`() {
        assertEquals(emptyList(), SearchText.queryPhrases(""))
        assertEquals(emptyList(), SearchText.queryPhrases("a * \" '"))
        assertEquals(emptyList(), SearchText.queryPhrases("😀😀"))
    }

    @Test
    fun `phrase grams join the overlapping pieces without break markers`() {
        assertEquals("방끈", SearchText.phraseGrams("방끈"))
        assertEquals("가방 방끈", SearchText.phraseGrams("가방끈"))
    }

    // ---- 하이라이트 (UTF-16 위치, 자바스크립트 문자열과 같다) ----

    @Test
    fun `highlights are half-open UTF-16 ranges and ignore case`() {
        assertEquals(listOf(1 to 3), SearchText.highlights("가방끈 길이", listOf("방끈")))
        assertEquals(listOf(0 to 2, 6 to 8), SearchText.highlights("Hello hello", listOf("he")))
    }

    @Test
    fun `a supplementary-plane emoji before the match shifts positions by two units`() {
        val text = "😀 가방끈"
        val range = SearchText.highlights(text, listOf("방끈")).single()
        assertEquals(text.indexOf("방끈") to text.indexOf("방끈") + 2, range)
        assertEquals(4 to 6, range)
    }

    @Test
    fun `a match followed by a combining mark takes the mark along`() {
        // x + U+0301 은 합쳐진 글자가 없어 NFKC 뒤에도 따로다 - 강조가 악센트 앞에서 끊기면 안 된다
        assertEquals(listOf(0 to 3), SearchText.highlights("ax́b", listOf("ax")))
    }

    @Test
    fun `overlapping and adjacent ranges from several phrases merge`() {
        assertEquals(listOf(0 to 5), SearchText.highlights("가방끈길이", listOf("가방끈", "끈길이")))
        assertEquals(listOf(0 to 4), SearchText.highlights("가방끈길", listOf("가방", "끈길")))
    }

    @Test
    fun `no phrases or no match yields no ranges`() {
        assertEquals(emptyList(), SearchText.highlights("가방끈", emptyList()))
        assertEquals(emptyList(), SearchText.highlights("가방끈", listOf("zz")))
    }

    // ---- 발췌 ----

    @Test
    fun `snippet is the body around the first match with ellipses and shifted ranges`() {
        val body = "가".repeat(200) + "방끈" + "나".repeat(200)
        val snippet = SearchText.snippet(body, listOf("방끈"), radius = 10)
        assertEquals("…" + "가".repeat(10) + "방끈" + "나".repeat(10) + "…", snippet.text)
        assertEquals(listOf(11 to 13), snippet.highlights)
        assertEquals("방끈", snippet.text.substring(11, 13))
    }

    @Test
    fun `snippet without a body match is the head of the body without ranges`() {
        val snippet = SearchText.snippet("가방끈 이야기".repeat(100), listOf("zz"), radius = 10)
        assertEquals(emptyList(), snippet.highlights)
        assertTrue(snippet.text.startsWith("가방끈 이야기"))
        assertTrue(snippet.text.endsWith("…"))
    }

    @Test
    fun `snippet of a short body is the whole body and an empty body gives an empty snippet`() {
        assertEquals("가방끈", SearchText.snippet("가방끈", listOf("방끈"), radius = 120).text)
        assertEquals("", SearchText.snippet("", listOf("방끈")).text)
    }

    @Test
    fun `snippet never splits a surrogate pair and keeps newlines as single spaces so ranges stay aligned`() {
        val body = "😀".repeat(30) + "방끈" + "😀".repeat(30)
        val snippet = SearchText.snippet(body, listOf("방끈"), radius = 5)
        val text = snippet.text
        text.forEachIndexed { i, c ->
            if (c.isHighSurrogate()) assertTrue(i + 1 < text.length && text[i + 1].isLowSurrogate(), "lone high surrogate at $i")
            if (c.isLowSurrogate()) assertTrue(i > 0 && text[i - 1].isHighSurrogate(), "lone low surrogate at $i")
        }
        val (from, to) = snippet.highlights.single()
        assertEquals("방끈", snippet.text.substring(from, to))
        assertEquals("a b c", SearchText.snippet("a\nb\tc", emptyList()).text)
    }
}
