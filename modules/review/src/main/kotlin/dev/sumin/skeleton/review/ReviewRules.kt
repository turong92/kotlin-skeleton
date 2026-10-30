package dev.sumin.skeleton.review

/** 대상 종류 · id 규칙 한 곳 - 종류는 설정 목록에 있어야 하고, 있는지는 앱의 [ReviewTargets] 가 답한다(빈이 없으면 id 모양만 본다) */
class ReviewTargetRules(
    private val properties: ReviewProperties,
    private val targets: () -> ReviewTargets?,
) {
    /** 열려 있는 종류만 통과한다. 모양이 틀린 종류도 같은 404 - 어느 쪽인지 알려 주지 않는다 */
    fun requireType(type: String): String {
        if (type !in properties.targetTypes) throw notFound()
        return type
    }

    /** 종류와 id 모양을 본다 - 읽기용. 대상이 진짜 있는지는 보지 않는다 */
    fun requireShape(target: ReviewTarget): ReviewTarget {
        requireType(target.type)
        if (!validId(target.id)) throw notFound()
        return target
    }

    /** 쓰기용 - 앱이 대상이 있다고 답해야 한다 */
    fun requireExists(target: ReviewTarget): ReviewTarget {
        requireShape(target)
        val gate = targets() ?: return target
        if (target.id !in gate.existing(target.type, listOf(target.id))) throw notFound()
        return target
    }

    private fun validId(id: String) = id.isNotBlank() && id.length <= MAX_ID && id.none { Character.isISOControl(it) }

    private fun notFound() = ReviewException(ReviewErrorCode.TARGET_NOT_FOUND, "Review target not found")

    companion object {
        const val MAX_ID = 64
    }
}

/**
 * 리뷰 입력의 길이 · 제어문자 규칙. HTML 은 해석하지 않는다 - 문자열 그대로 저장하고 그릴 때 클라이언트가 이스케이프한다(`board` 와 같다).
 * 지우는 것: 줄바꿈 · 탭 외의 제어문자(NUL 포함)와 양방향 덮어쓰기 문자(U+202A–202E, U+2066–2069). 길이는 코드 포인트로 센다.
 */
class ReviewContentRules(private val properties: ReviewProperties) {
    fun rating(raw: Int?): Int {
        if (raw == null || raw !in 1..5) invalid("rating must be 1..5")
        return raw
    }

    fun body(raw: String?): String {
        val text = clean(raw)
        val length = text.codePointCount(0, text.length)
        if (length < properties.bodyMinLength) invalid("body must be at least ${properties.bodyMinLength} characters")
        if (length > properties.bodyMaxLength) invalid("body must be at most ${properties.bodyMaxLength} characters")
        return text
    }

    /**
     * 사진 저장 키 - 올리기는 앱이 storage 로 한다. null 이면 바꾸지 않는다. 빈 목록은 사진을 비운다.
     * **키에 작성자 계정 id 가 들어 있으면 거절한다** - 보여 줄 때 서버가 키로 서명 주소를 만들어 넘기므로, 계정 id 가 든 키는 다른 사람에게 새 나간다.
     */
    fun photos(raw: List<String>?, accountId: String): List<String>? {
        if (raw == null) return null
        val keys = raw.map { it.trim() }
        if (keys.any { it.isEmpty() || it.length > MAX_KEY_LENGTH }) invalid("photo keys must be 1..$MAX_KEY_LENGTH characters")
        val distinct = keys.distinct()
        if (distinct.size > properties.maxPhotos) invalid(if (properties.maxPhotos == 0) "photos are not enabled" else "at most ${properties.maxPhotos} photos")
        if (distinct.any { it.contains(accountId) }) invalid("photo keys must not contain the account id")
        return distinct
    }

    fun reportReason(raw: String?): ReportReason =
        ReportReason.entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: invalid("reason is one of ${ReportReason.entries.joinToString()}")

    fun reportDetail(raw: String?): String? = raw?.let { clean(it) }?.takeIf { it.isNotEmpty() }?.also { limit("detail", it, MAX_DETAIL) }

    /** 운영자가 적는 숨김 사유 - 필수 */
    fun moderationReason(raw: String?): String = required("reason", raw, MAX_REASON)

    fun replyBody(raw: String?): String = required("reply body", raw, MAX_REPLY)

    private fun required(what: String, raw: String?, max: Int): String {
        val text = clean(raw)
        if (text.isEmpty()) invalid("$what must not be blank")
        return text.also { limit(what, it, max) }
    }

    private fun limit(what: String, text: String, max: Int) {
        if (text.codePointCount(0, text.length) > max) invalid("$what must be at most $max characters")
    }

    private fun clean(raw: String?): String {
        val normalised = (raw ?: "").replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(normalised.length)
        normalised.codePoints().forEach { cp ->
            when {
                cp == '\n'.code || cp == '\t'.code -> sb.append(cp.toChar())
                Character.isISOControl(cp) -> Unit
                cp in 0x202A..0x202E || cp in 0x2066..0x2069 -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().trim()
    }

    private fun invalid(message: String): Nothing = throw ReviewException(ReviewErrorCode.CONTENT_INVALID, message)

    private companion object {
        const val MAX_KEY_LENGTH = 500
        const val MAX_DETAIL = 500
        const val MAX_REASON = 200
        const val MAX_REPLY = 2000
    }
}
