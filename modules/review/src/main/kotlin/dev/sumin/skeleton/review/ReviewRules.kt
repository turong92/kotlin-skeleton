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

    /** 사진 조각 전까지는 사진 키를 받지 않는다 - 조용히 버리지 않고 거절한다 */
    fun photos(raw: List<String>?) {
        if (!raw.isNullOrEmpty()) invalid("photos are not enabled")
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
}
