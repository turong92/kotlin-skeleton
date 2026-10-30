package dev.sumin.skeleton.search

import java.text.Normalizer

/**
 * 방언 · 엔진과 무관한 글자 처리 한 곳 - 정규화 · 2글자 조각 · 질의어 쪼개기 · 하이라이트 위치. 바깥 엔진 어댑터도 하이라이트를 같은 식으로 쓴다.
 *
 * - **낱말 덩어리(run)**: 글자 · 숫자(어느 문자든)가 이어진 한 덩어리. 구두점 · 공백 · 이모지 · 밑줄 · 결합 문자는 덩어리를 가른다.
 * - **조각(gram)**: 덩어리 안의 겹치는 2글자(코드 포인트). 1글자 덩어리는 조각이 없다.
 * - 질의어는 덩어리 단위 **부분 문자열 일치**로 맞는다 - 조각이 이어져야(구문) 맞는다.
 */
object SearchText {
    /**
     * 조각열에서 덩어리 사이(와 한 부분의 끝)에 끼우는 표지 낱말. 질의 조각은 항상 정확히 2글자라 3글자 이상인 이 낱말과 같을 수 없다 -
     * 끼우지 않으면 서로 다른 낱말의 조각(`ab` `bc`)이나 제목 끝 · 본문 처음이 위치가 이어져 질의 `abc` 에 맞아 버린다 (K4 실측).
     */
    const val BREAK = "brk"

    /** 색인하는 본문 길이 - PostgreSQL tsvector 의 위치 상한(16,383)과 MySQL `text` 열(4바이트 글자 16,000자 = 64,000바이트)에 모두 들어간다 */
    const val MAX_BODY = 16_000
    const val MAX_PHRASES = 5
    const val SNIPPET_RADIUS = 120
    private const val ELLIPSIS = "…"

    /** NFKC (전각 · 반각 · 호환 문자를 하나로) 하고, 줄바꿈 · 탭 말고 제어 문자(NUL 포함 - PostgreSQL 은 NUL 을 거부한다)는 공백으로 */
    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = StringBuilder(nfkc.length)
        for (c in nfkc) out.append(if (Character.isISOControl(c) && c != '\n' && c != '\t') ' ' else c)
        return out.toString()
    }

    /** 앞 [max] UTF-16 단위까지 - 서로게이트 쌍을 가르지 않는다 */
    fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }

    /** 코드 포인트마다 소문자로 - 길이가 달라지면(드문 경우) 위치가 어긋나므로 그대로 둔다 */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { out.appendCodePoint(Character.toLowerCase(it)) }
        return if (out.length == text.length) out.toString() else text
    }

    /** 정규화 · 소문자 뒤의 낱말 덩어리들 */
    fun runs(text: String): List<String> {
        val folded = fold(normalize(text))
        val result = ArrayList<String>()
        val current = StringBuilder()
        folded.codePoints().forEach { cp ->
            if (Character.isLetterOrDigit(cp)) current.appendCodePoint(cp)
            else if (current.isNotEmpty()) { result += current.toString(); current.setLength(0) }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /** 문서 쪽 조각열 - PostgreSQL `to_tsvector('simple', …)` 에 넣는다. 덩어리마다 조각 뒤에 [BREAK] 를 끼운다 */
    fun grams(text: String): String =
        runs(text).flatMap { run -> pieces(run) + BREAK }.joinToString(" ")

    /** 질의 한 덩어리의 조각을 띄어 쓴 것 - `phraseto_tsquery` 가 이어진 구문으로 바꾼다 */
    fun phraseGrams(phrase: String): String = pieces(phrase).joinToString(" ")

    /** 질의 문자열 → 찾을 덩어리들. 2글자 미만은 버리고, 같은 것은 하나로, 최대 [MAX_PHRASES] 개. 비면 검색할 수 없는 질의다 */
    fun queryPhrases(query: String): List<String> =
        runs(query).filter { it.codePointCount(0, it.length) >= 2 }.distinct().take(MAX_PHRASES)

    /** [text](저장된 정규화 원문) 안에서 [phrases] 가 나오는 곳 `[시작, 끝)` - UTF-16 위치라 자바스크립트 문자열의 위치와 같다. 겹치거나 맞닿은 곳은 하나로 */
    fun highlights(text: String, phrases: List<String>): List<Pair<Int, Int>> {
        if (phrases.isEmpty() || text.isEmpty()) return emptyList()
        val folded = fold(text)
        val found = ArrayList<Pair<Int, Int>>()
        for (phrase in phrases) {
            var at = folded.indexOf(phrase)
            while (at >= 0) {
                found += at to extendOverMarks(folded, at + phrase.length)
                at = folded.indexOf(phrase, at + 1)
            }
        }
        val merged = ArrayList<Pair<Int, Int>>()
        for (range in found.sortedWith(compareBy({ it.first }, { it.second }))) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, range.second)
            else merged += range
        }
        return merged
    }

    data class Snippet(val text: String, val highlights: List<Pair<Int, Int>>)

    /** 본문 발췌 - 첫 일치 앞뒤 [radius] 글자(없으면 본문 머리). 잘린 쪽에 `…` 를 붙이고, 줄바꿈 · 탭은 공백 하나로(위치가 어긋나지 않게 1:1) */
    fun snippet(body: String, phrases: List<String>, radius: Int = SNIPPET_RADIUS): Snippet {
        if (body.isEmpty()) return Snippet("", emptyList())
        val flat = body.map { if (it == '\n' || it == '\t' || it == '\r') ' ' else it }.joinToString("")
        val ranges = highlights(flat, phrases)
        val first = ranges.firstOrNull()
        var start = if (first == null) 0 else maxOf(0, first.first - radius)
        var end = if (first == null) minOf(flat.length, radius * 2) else minOf(flat.length, first.second + radius)
        if (start in 1 until flat.length && flat[start].isLowSurrogate()) start++
        if (end in 1 until flat.length && flat[end - 1].isHighSurrogate()) end--
        val prefix = if (start > 0) ELLIPSIS else ""
        val text = prefix + flat.substring(start, end) + if (end < flat.length) ELLIPSIS else ""
        val shifted = ranges.mapNotNull { (from, to) ->
            val clippedFrom = maxOf(from, start)
            val clippedTo = minOf(to, end)
            if (clippedFrom < clippedTo) (clippedFrom - start + prefix.length) to (clippedTo - start + prefix.length) else null
        }
        return Snippet(text, shifted)
    }

    private fun pieces(run: String): List<String> {
        val points = run.codePoints().toArray()
        return (0 until points.size - 1).map { String(points, it, 2) }
    }

    private fun extendOverMarks(text: String, end: Int): Int {
        var i = end
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) i += Character.charCount(cp)
            else break
        }
        return i
    }
}
