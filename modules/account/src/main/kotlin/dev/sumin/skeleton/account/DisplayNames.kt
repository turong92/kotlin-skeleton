package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.FieldValidationException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.HexFormat
import java.util.Locale
import java.util.random.RandomGenerator

/** 닉네임이 거절되는 이유 — [fieldCode] 는 `400` 의 `errors[].code` (Bean Validation 의 이름을 따른다) */
enum class NameProblem(val fieldCode: String, val message: String) {
    EMPTY("Required", "Display name is required"),
    TOO_LONG("Size", "Display name must be 1 to ${DisplayNameRules.MAX} characters"),
    INVALID_CHARACTERS("Pattern", "Display name has characters that are not allowed (control or invisible characters, # and @, or a deleted: prefix)"),
    RESERVED("Reserved", "This display name is reserved"),
}

/**
 * 닉네임 규칙 — **여기 한 곳**이다 (가입 · 프로필 수정 · 제공자 이름 · 시드 모두 이것을 거친다). 사람이 쓴 값은 [problemOf] 로 **거절**하고,
 * 제공자 · 시드가 준 값은 [sanitize] 로 **고쳐 쓴다** (가입을 막을 수 없다).
 *
 * 정리 [clean]: 앞뒤 공백 제거 · 공백류(NBSP · 전각 공백 …)는 일반 공백 하나로 · 연속 공백은 한 칸.
 * 거절: 1..[MAX] 글자(코드 포인트) · 제어 문자 · 줄바꿈 · 보이지 않는 문자(zero-width · 방향 제어 · 한글 채움 문자 · 점자 빈칸 …) ·
 * `#` · `@`(꼬리표 · 멘션과 헷갈린다 — 전각 `＃` `＠` 도 NFKC 로 같은 글자) · `deleted:` 로 시작(톰스톤과 같은 모양) · 보이는 글자가 하나도 없음.
 * 비교용 키 [key]: NFKC(호환 문자 · 전각 · 반각 · 결합 문자) → 소문자 → NFKC. 서로 닮은 글자(키릴 a 와 라틴 a 같은 동형 문자)까지 같게 보지는 **않는다** — 최소선이다.
 */
object DisplayNameRules {
    const val MAX = 60

    /** 키 열의 길이 — NFKC 는 한 글자를 최대 18자까지 늘린다(U+FDFA). 넘으면 닉네임으로 받지 않는다 */
    const val MAX_KEY = 255

    private const val TOMBSTONE = "deleted:"

    /** 글자 모양이 없는데 [Character.getType] 으로는 못 거르는 것 — 한글 채움 문자 · 점자 빈칸 · 크메르 내재 모음 */
    private val BLANK_LOOKING = setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x17B4, 0x17B5)

    fun clean(raw: String): String {
        val spaced = buildString {
            raw.codePoints().forEach { cp -> if (Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()) append(' ') else appendCodePoint(cp) }
        }
        return spaced.trim().replace(Regex(" {2,}"), " ")
    }

    fun key(cleaned: String): String = nfkc(nfkc(cleaned).lowercase(Locale.ROOT))

    /** [cleaned] (= [clean] 한 값)가 닉네임이 될 수 없는 이유, 괜찮으면 null */
    fun problemOf(cleaned: String): NameProblem? {
        if (cleaned.isEmpty()) return NameProblem.EMPTY
        if (cleaned.codePointCount(0, cleaned.length) > MAX) return NameProblem.TOO_LONG
        if (cleaned.codePoints().anyMatch { forbidden(it) }) return NameProblem.INVALID_CHARACTERS
        val key = key(cleaned)
        if (key.startsWith(TOMBSTONE)) return NameProblem.INVALID_CHARACTERS
        if (key.codePoints().noneMatch { visible(it) }) return NameProblem.INVALID_CHARACTERS
        if (key.length > MAX_KEY) return NameProblem.TOO_LONG
        return null
    }

    /** 제공자 · 시드가 준 이름을 규칙에 맞게 고친다 — 맞출 수 없으면 null (이름 없음) */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        var name = clean(buildString { raw.codePoints().filter { !forbidden(it) }.forEach { appendCodePoint(it) } })
        repeat(3) { if (key(name).startsWith(TOMBSTONE)) name = clean(name.drop(TOMBSTONE.length)) }
        name = clean(name.codePoints().limit(MAX.toLong()).toArray().let { String(it, 0, it.size) })
        return name.takeIf { problemOf(it) == null }
    }

    /** 예약어 목록 → 비교용 값. 글자 · 숫자만 남긴 키라서 `ad min` · `ad_min` · `ＡＤＭＩＮ` 이 `admin` 과 같다 */
    fun reservedKeys(words: Collection<String>): Set<String> = words.map { squash(key(clean(it))) }.filter { it.isNotEmpty() }.toSet()

    fun isReserved(name: String, reserved: Set<String>): Boolean = reserved.isNotEmpty() && squash(key(clean(name))).let { it.isNotEmpty() && it in reserved }

    private fun squash(key: String) = buildString { key.codePoints().filter { Character.isLetterOrDigit(it) }.forEach { appendCodePoint(it) } }

    private fun nfkc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)

    /** 닉네임에 들어갈 수 없는 글자 하나 */
    private fun forbidden(cp: Int): Boolean {
        if (cp in BLANK_LOOKING) return true
        when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            -> return true
        }
        return nfkc(String(Character.toChars(cp))).let { '#' in it || '@' in it }
    }

    /** 눈에 보이는 글자(글자 · 숫자 · 기호 · 문장부호 · 이모지) */
    private fun visible(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.CONNECTOR_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        -> true
        else -> false
    }
}

/**
 * 설정(`skeleton.account.display-name.*`)이 얹힌 닉네임 규칙 · 꼬리표 · 자동 닉네임.
 * 저장 규칙: 키는 **언제나** 저장하고(방식을 나중에 바꿔도 다시 계산할 필요가 없다), 꼬리표 열이 방식을 가른다 — `NONE`: NULL(유니크에 걸리지 않는다) ·
 * `UNIQUE`: [NO_TAG] 고정(키 하나에 하나) · `TAGGED`: 키 안에서 겹치지 않는 `0001`..`9999`.
 */
class DisplayNames(val config: AccountProperties.DisplayName, private val random: RandomGenerator = SecureRandom()) {
    private val reserved = DisplayNameRules.reservedKeys(config.reserved)

    val uniqueness: AccountProperties.DisplayName.Uniqueness get() = config.uniqueness

    /**
     * 사람이 낸 닉네임을 규칙에 맞춰 받는다 — 정리한 값(없으면 null). 어긋나면 `400` 필드 오류 `displayName`.
     * [required]: 없는 값(null · 공백)도 오류 `Required`. [exemptReserved]: 예약어 검사를 건너뛴다 (운영자 역할 계정).
     * **계정 저장소를 읽지 않는다** — 가입 요청에서 주소가 있든 없든 같은 시점 · 같은 응답이어야 한다.
     */
    fun accept(raw: String?, required: Boolean = false, exemptReserved: Boolean = false): String? {
        val cleaned = raw?.let(DisplayNameRules::clean).orEmpty()
        if (cleaned.isEmpty()) {
            if (required) throw FieldValidationException(FIELD, NameProblem.EMPTY.fieldCode, NameProblem.EMPTY.message)
            return null
        }
        DisplayNameRules.problemOf(cleaned)?.let { throw FieldValidationException(FIELD, it.fieldCode, it.message) }
        if (!exemptReserved && DisplayNameRules.isReserved(cleaned, reserved)) {
            throw FieldValidationException(FIELD, NameProblem.RESERVED.fieldCode, NameProblem.RESERVED.message)
        }
        return cleaned
    }

    /** `user-1a2b3c` — 무작위 여섯 자리 16진수 (이메일 · 계정 id 에서 만들지 않는다) */
    fun generated(): String = PREFIX + HexFormat.of().formatHex(ByteArray(3).also { b -> random.nextBytes(b) })

    /** 이 방식이 저장할 꼬리표의 후보 하나 — NONE 은 없다(null), UNIQUE 는 고정값, TAGGED 는 무작위 */
    fun tagCandidate(): String? = when (uniqueness) {
        AccountProperties.DisplayName.Uniqueness.NONE -> null
        AccountProperties.DisplayName.Uniqueness.UNIQUE -> NO_TAG
        AccountProperties.DisplayName.Uniqueness.TAGGED -> tag(1 + random.nextInt(MAX_TAG))
    }

    /** 키 안에서 아직 안 쓰인 꼬리표 하나(무작위) — 다 찼으면 null. 무작위 시도가 몇 번 겹친 뒤의 마지막 길이다 */
    fun freeTag(used: Set<String>): String? {
        val free = (1..MAX_TAG).map(::tag).filter { it !in used }
        return if (free.isEmpty()) null else free[random.nextInt(free.size)]
    }

    companion object {
        const val FIELD = "displayName"
        const val PREFIX = "user-"

        /** UNIQUE 방식이 저장하는 고정 꼬리표 — 사람에게는 꼬리표가 아니다(응답에 나가지 않는다) */
        const val NO_TAG = "0000"
        const val MAX_TAG = 9999

        fun tag(n: Int): String = n.toString().padStart(4, '0')

        /** 응답에 나갈 꼬리표 — 저장된 값이 없거나 [NO_TAG] 면 null */
        fun visibleTag(stored: String?): String? = stored?.takeIf { it != NO_TAG }
    }
}
