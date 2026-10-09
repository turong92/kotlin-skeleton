package dev.sumin.skeleton.account

import java.util.Locale

/** 로그인 아이디 형식 검사의 결과 — [code] 는 `errors[].code`(`Required` · `Size` · `Pattern`) 그대로 나간다 */
enum class LoginIdProblem(val code: String) { REQUIRED("Required"), SIZE("Size"), PATTERN("Pattern") }

/**
 * 로그인 아이디 규칙 — **여기 한 곳**이다 (가입 · 실시간 확인 · 정하기 · 로그인 칸의 판별이 모두 이 규칙을 쓴다).
 * 정규화: 앞뒤 공백 제거 → **ASCII 글자만** 소문자. 전각 · 한글 · 켈빈 기호(`K` U+212A, 전체 유니코드 소문자화는 `k` 로 바꾼다) 같은 글자는 고쳐 주지 않고 [LoginIdProblem.PATTERN] 으로 거절한다.
 * 모양: `^[a-z0-9]+([._-][a-z0-9]+)*$` (기호는 맨 앞 · 맨 뒤 · 연달아 올 수 없다) + 영문이 하나는 있어야 한다 + 길이.
 * **길이가 모양보다 먼저다 (열 길이 초과면 모양을 보지 않는다)** — 공개 경로의 입력은 상한이 없고, 모양은 정규식이 아닌 한 번 훑는 검사라 재귀하지 않는다.
 * 예약어는 형식 문제가 아니라 [isReserved] 로 따로 본다 (기호와 대소문자를 빼고 비교).
 */
object LoginIds {
    /** 열 길이 — 설정의 `max-length` 는 이 값을 넘을 수 없다 */
    const val COLUMN_LENGTH = 30

    private val SYMBOLS = charArrayOf('.', '-', '_')

    /** 응답에 되돌려 보내도 되는 길이로 자른다 (열 길이) — 서로게이트 쌍의 앞 절반만 남기지 않는다 */
    fun clip(text: String): String =
        if (text.length <= COLUMN_LENGTH) text else text.take(COLUMN_LENGTH).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }

    fun normalize(raw: String): String = asciiLower(raw.trim())

    /** ASCII 대문자만 소문자로 — 켈빈 기호 · 점 있는 İ 같은 글자가 소문자화로 ASCII 가 되어 통과하지 못하게 */
    private fun asciiLower(text: String): String {
        if (text.none { it in 'A'..'Z' }) return text
        return buildString(text.length) { text.forEach { c -> append(if (c in 'A'..'Z') c + ('a' - 'A') else c) } }
    }

    /**
     * [normalized] 는 [normalize] 를 거친 값이다. 형식이 맞으면 null. 열 길이를 넘는 입력은 모양을 보지 않고 바로 [LoginIdProblem.SIZE]이고,
     * 그 아래에서는 모양이 [minLength] · [maxLength] 보다 먼저다 (`12` 는 Pattern)
     */
    fun problemOf(normalized: String, minLength: Int, maxLength: Int): LoginIdProblem? {
        if (normalized.isEmpty()) return LoginIdProblem.REQUIRED
        if (normalized.length > COLUMN_LENGTH) return LoginIdProblem.SIZE
        if (!shapeOk(normalized) || normalized.none { it in 'a'..'z' }) return LoginIdProblem.PATTERN
        if (normalized.length < minLength || normalized.length > maxLength) return LoginIdProblem.SIZE
        return null
    }

    /** `^[a-z0-9]+([._-][a-z0-9]+)*$` — 정규식 없이 한 번 훑는다 (기호는 맨 앞 · 맨 뒤 · 연달아 올 수 없다) */
    private fun shapeOk(s: String): Boolean {
        var previousAlnum = false
        for (c in s) {
            val alnum = c in 'a'..'z' || c in '0'..'9'
            if (alnum) previousAlnum = true
            else if (c in SYMBOLS && previousAlnum) previousAlnum = false
            else return false
        }
        return previousAlnum
    }

    /** [reserved] 의 항목과 같은 아이디인가 — 양쪽 모두 기호(`.` `-` `_`)와 대소문자를 빼고 비교한다 (`ad.min` · `ad_min` = `admin`) */
    fun isReserved(normalized: String, reserved: Collection<String>): Boolean {
        val key = bare(normalized)
        return key.isNotEmpty() && reserved.any { bare(it) == key }
    }

    /** 로그인 칸에서 `@` 가 있으면 이메일, 없으면 아이디 */
    fun looksLikeEmail(username: String): Boolean = '@' in username

    private fun bare(value: String): String = value.lowercase(Locale.ROOT).filter { it !in SYMBOLS }
}
