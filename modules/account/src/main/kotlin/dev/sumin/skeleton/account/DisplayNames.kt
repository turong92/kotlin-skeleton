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
 * 닉네임 규칙 — **교체 가능한 한 곳**이다 (가입 · 프로필 수정 · 제공자 이름 · 시드 모두 이것을 거친다). 앱이 이 타입의 빈을 두면 [DefaultDisplayNameRules] 대신 쓰인다
 * (예: 페르시아어 ZWNJ · 인도계 문자의 ZWJ 를 허용하는 규칙). 사람이 쓴 값은 [problemOf] 로 **거절**하고, 제공자 · 시드가 준 값은 [sanitize] 로 **고쳐 쓴다** (가입을 막을 수 없다).
 * 예약어는 설정(`display-name.reserved`)이라 [DisplayNames] 가 이 규칙의 [clean] · [key] 위에서 맞춘다 — 규칙을 바꿔도 예약어는 그대로 걸린다.
 */
interface DisplayNameRules {
    /** 저장하고 보여 줄 모양으로 정리한다 (공백 정리 등). 보이지 않는 문자를 [key] 에서만 무시하더라도 표시 문자열에는 남는다 */
    fun clean(raw: String): String

    /** 비교용 키 — 화면에서 같게 보이는 닉네임이 같은 키가 되게. 같은 키는 UNIQUE 에서 충돌하고 TAGGED 에서 같은 꼬리표 줄을 쓴다 */
    fun key(cleaned: String): String

    /** [cleaned] (= [clean] 한 값)가 닉네임이 될 수 없는 이유, 괜찮으면 null */
    fun problemOf(cleaned: String): NameProblem?

    /** 제공자 · 시드가 준 이름을 규칙에 맞게 고친다 — 맞출 수 없으면 null (이름 없음) */
    fun sanitize(raw: String?): String?

    /** 예약어 목록 → 비교용 값. 글자 · 숫자만 남긴 키라서 `ad min` · `ad_min` · `ＡＤＭＩＮ` 이 `admin` 과 같다 */
    fun reservedKeys(words: Collection<String>): Set<String> = words.map { squash(key(clean(it))) }.filter { it.isNotEmpty() }.toSet()

    fun isReserved(name: String, reserved: Set<String>): Boolean = reserved.isNotEmpty() && squash(key(clean(name))).let { it.isNotEmpty() && it in reserved }

    private fun squash(key: String) = buildString { key.codePoints().filter { Character.isLetterOrDigit(it) }.forEach { appendCodePoint(it) } }

    companion object {
        const val MAX = 60

        /** 키 열의 길이 — NFKC 는 한 글자를 최대 18자까지 늘린다(U+FDFA). 넘으면 닉네임으로 받지 않는다 */
        const val MAX_KEY = 255
    }
}

/**
 * 기본 닉네임 규칙.
 *
 * 정리 [clean]: 앞뒤 공백 제거 · 공백류(NBSP · 전각 공백 …)는 일반 공백 하나로 · 연속 공백은 한 칸.
 * 거절: 1..[DisplayNameRules.MAX] 글자(코드 포인트) · 제어 문자 · 줄바꿈 · 보이지 않는 문자(zero-width · 방향 제어 · 한글 채움 문자 · 점자 빈칸 · CGJ · 태그 문자 …) ·
 * `#` · `@`(꼬리표 · 멘션과 헷갈린다 — 전각 `＃` `＠` 도 NFKC 로 같은 글자) · `deleted:` 로 시작(톰스톤과 같은 모양) · 보이는 글자가 하나도 없음.
 * **예외 둘** (Unicode Default_Ignorable_Code_Point 중): ① 변형 선택자(U+FE00–FE0F · U+E0100–E01EF — 이모지 ❤️ · 한자 이체자)는 보이는 글자 **바로 뒤에** 하나 ② ZWJ(U+200D)는
 * 두 이모지(Extended_Pictographic) 사이에서만 — 👩‍💻 같은 시퀀스. 이 둘은 **표시 문자열에는 남고** 비교용 키에서는 **무시된다**(`수민` 과 `수민` + U+FE0F 는 같은 키).
 * 비교용 키 [key]: NFKC(호환 문자 · 전각 · 반각 · 결합 문자) → 소문자 → Default_Ignorable 떨구기 → NFKC. 서로 닮은 글자(키릴 a 와 라틴 a 같은 동형 문자)까지 같게 보지는 **않는다** — 최소선이다.
 */
object DefaultDisplayNameRules : DisplayNameRules {
    private const val TOMBSTONE = "deleted:"

    /** 글자 모양이 없는데 [Character.getType] 으로는 못 거르는 것 — 점자 빈칸 (나머지 채움 문자는 Default_Ignorable 이다) */
    private val BLANK_LOOKING = setOf(0x2800)

    override fun clean(raw: String): String {
        val spaced = buildString {
            raw.codePoints().forEach { cp -> if (Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()) append(' ') else appendCodePoint(cp) }
        }
        return spaced.trim().replace(Regex(" {2,}"), " ")
    }

    override fun key(cleaned: String): String = nfkc(withoutIgnorable(nfkc(cleaned).lowercase(Locale.ROOT)))

    override fun problemOf(cleaned: String): NameProblem? {
        if (cleaned.isEmpty()) return NameProblem.EMPTY
        if (cleaned.codePointCount(0, cleaned.length) > DisplayNameRules.MAX) return NameProblem.TOO_LONG
        val cps = cleaned.codePoints().toArray()
        for (i in cps.indices) {
            val ok = if (isIgnorable(cps[i])) ignorableAllowed(cps, i, cps[i], cps.getOrNull(i + 1)) else !forbidden(cps[i])
            if (!ok) return NameProblem.INVALID_CHARACTERS
        }
        val key = key(cleaned)
        if (key.startsWith(TOMBSTONE)) return NameProblem.INVALID_CHARACTERS
        if (key.codePoints().noneMatch { visible(it) }) return NameProblem.INVALID_CHARACTERS
        if (key.length > DisplayNameRules.MAX_KEY) return NameProblem.TOO_LONG
        return null
    }

    override fun sanitize(raw: String?): String? {
        if (raw == null) return null
        // 1) 맥락 없이 못 쓰는 글자를 버린다 2) 보이지 않는 글자는 허용된 자리에 있는 것만 남긴다
        val first = raw.codePoints().filter { isIgnorable(it) || !forbidden(it) }.toArray()
        val kept = IntArray(first.size)
        var n = 0
        for (i in first.indices) if (!isIgnorable(first[i]) || ignorableAllowed(kept, n, first[i], first.getOrNull(i + 1))) kept[n++] = first[i]
        var name = clean(String(kept, 0, n))
        repeat(3) { if (key(name).startsWith(TOMBSTONE)) name = clean(name.drop(TOMBSTONE.length)) }
        name = clean(name.codePoints().limit(DisplayNameRules.MAX.toLong()).toArray().let { String(it, 0, it.size) })
        return name.takeIf { problemOf(it) == null }
    }

    private fun nfkc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)

    private fun withoutIgnorable(s: String) = buildString { s.codePoints().filter { !isIgnorable(it) }.forEach { appendCodePoint(it) } }

    /**
     * 보이지 않는 글자 [cp] 가 [before] 의 앞 [size] 개(이미 받아들인 글자) 뒤, [next] 앞에 있어도 되나.
     * 변형 선택자: 바로 앞이 보이는 글자. ZWJ: 앞(변형 선택자 · 이모지 수식자를 건너뛴 글자)과 뒤가 모두 이모지.
     */
    private fun ignorableAllowed(before: IntArray, size: Int, cp: Int, next: Int?): Boolean {
        val last = if (size > 0) before[size - 1] else null
        return when {
            isVariationSelector(cp) -> last != null && !isIgnorable(last) && visible(last)
            cp == ZWJ -> next != null && Character.isExtendedPictographic(next) &&
                (size - 1 downTo 0).map { before[it] }.firstOrNull { !isVariationSelector(it) && !Character.isEmojiModifier(it) }?.let { Character.isExtendedPictographic(it) } == true
            else -> false
        }
    }

    private fun isVariationSelector(cp: Int) = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    private const val ZWJ = 0x200D

    /** Unicode Default_Ignorable_Code_Point (DerivedCoreProperties) — 화면에 아무것도 그리지 않는 코드 포인트 */
    private fun isIgnorable(cp: Int): Boolean = when (cp) {
        0x00AD, 0x034F, 0x061C, 0x3164, 0xFEFF, 0xFFA0 -> true
        in 0x115F..0x1160, in 0x17B4..0x17B5, in 0x180B..0x180F, in 0x200B..0x200F, in 0x202A..0x202E, in 0x2060..0x206F,
        in 0xFE00..0xFE0F, in 0xFFF0..0xFFF8, in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A, in 0xE0000..0xE0FFF,
        -> true
        else -> false
    }

    /** 닉네임에 들어갈 수 없는 글자 하나 ([isIgnorable] 은 자리를 보고 따로 가린다) */
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
class DisplayNames(
    val config: AccountProperties.DisplayName,
    private val random: RandomGenerator = SecureRandom(),
    /** 교체 가능한 규칙 — 앱이 `DisplayNameRules` 빈을 두면 그것 */
    val rules: DisplayNameRules = DefaultDisplayNameRules,
) {
    private val reserved = rules.reservedKeys(config.reserved)

    /** 비교용 키 ([DisplayNameRules.key]) — 저장 · 충돌 판정이 모두 이것을 쓴다 */
    fun key(cleaned: String): String = rules.key(cleaned)

    /**
     * 제공자 · 시드가 준 이름을 규칙에 맞게 고쳐 쓴다 ([DisplayNameRules.sanitize]) — 맞출 수 없으면 null.
     * **예약어에 걸리면 이름을 버린다**(null → 자동 닉네임 · 이름 없이): 제공자 이름으로 `display-name.reserved` 를 우회하지 못한다. [exemptReserved]: 시드(운영자가 정한 계정)만 건너뛴다.
     */
    fun sanitize(raw: String?, exemptReserved: Boolean = false): String? =
        rules.sanitize(raw)?.takeIf { exemptReserved || !rules.isReserved(it, reserved) }

    val uniqueness: AccountProperties.DisplayName.Uniqueness get() = config.uniqueness

    /**
     * 사람이 낸 닉네임을 규칙에 맞춰 받는다 — 정리한 값(없으면 null). 어긋나면 `400` 필드 오류 `displayName`.
     * [required]: 없는 값(null · 공백)도 오류 `Required`. [exemptReserved]: 예약어 검사를 건너뛴다 (운영자 역할 계정).
     * **계정 저장소를 읽지 않는다** — 가입 요청에서 주소가 있든 없든 같은 시점 · 같은 응답이어야 한다.
     */
    fun accept(raw: String?, required: Boolean = false, exemptReserved: Boolean = false): String? {
        val cleaned = raw?.let(rules::clean).orEmpty()
        if (cleaned.isEmpty()) {
            if (required) throw FieldValidationException(FIELD, NameProblem.EMPTY.fieldCode, NameProblem.EMPTY.message)
            return null
        }
        rules.problemOf(cleaned)?.let { throw FieldValidationException(FIELD, it.fieldCode, it.message) }
        if (!exemptReserved && rules.isReserved(cleaned, reserved)) {
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
