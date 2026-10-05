package dev.sumin.skeleton.board

/**
 * 글 · 댓글 텍스트의 길이 · 공백 · 제어문자 규칙. HTML 은 해석하지 않는다 — 문자열 그대로 저장하고, 그릴 때 클라이언트가 이스케이프한다.
 * 지우는 것: 줄바꿈 · 탭 외의 제어문자(NUL 포함)와 양방향 덮어쓰기 문자(U+202A–202E, U+2066–2069 — 글자 순서를 속이는 데 쓰인다). 길이는 코드 포인트로 센다.
 */
class BoardContentRules(private val properties: BoardProperties) {
    fun title(raw: String?): String {
        val text = clean(raw, multiline = false)
        return text.also { checkLength("title", it, properties.titleMaxLength) }
    }

    fun postBody(raw: String?): String =
        clean(raw, multiline = true).also { checkLength("body", it, properties.bodyMaxLength) }

    fun commentBody(raw: String?): String =
        clean(raw, multiline = true).also { checkLength("comment body", it, properties.commentMaxLength) }

    fun attachments(raw: List<String>?): List<String> {
        val keys = raw.orEmpty().map { it.trim() }
        if (keys.any { it.isEmpty() || it.length > MAX_KEY_LENGTH }) invalid("attachment keys must be 1..$MAX_KEY_LENGTH characters")
        val distinct = keys.distinct()
        if (distinct.size > properties.maxAttachments) invalid("at most ${properties.maxAttachments} attachments")
        return distinct
    }

    /** 검색어 — 앞뒤 공백을 지우고, 비면 null, 너무 길면 자른다 */
    fun searchTerm(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > MAX_SEARCH) it.substring(0, MAX_SEARCH) else it }

    private fun clean(raw: String?, multiline: Boolean): String {
        val normalised = (raw ?: "").replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(normalised.length)
        normalised.codePoints().forEach { cp ->
            when {
                cp == '\n'.code -> sb.append(if (multiline) "\n" else " ")
                cp == '\t'.code -> sb.append(if (multiline) "\t" else " ")
                Character.isISOControl(cp) -> Unit
                cp in 0x202A..0x202E || cp in 0x2066..0x2069 -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().trim()
    }

    private fun checkLength(what: String, text: String, max: Int) {
        if (text.isEmpty()) invalid("$what must not be blank")
        if (text.codePointCount(0, text.length) > max) invalid("$what must be at most $max characters")
    }

    private fun invalid(message: String): Nothing = throw BoardException(BoardErrorCode.CONTENT_INVALID, message)

    private companion object {
        const val MAX_KEY_LENGTH = 1024
        const val MAX_SEARCH = 100
    }
}
