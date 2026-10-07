package dev.sumin.skeleton.account

import java.time.ZoneId

/** 프로필 값 정리 — 모르는 로케일 · 시간대는 저장하지 않고(null) 요청을 깨뜨리지도 않는다. HTTP 는 따로 400 으로 검증한다 */
object ProfileRules {
    private val LOCALE = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}$")

    /** 제공자 · 시드가 준 이름을 닉네임 규칙([DisplayNameRules])에 맞게 고친다 — 사람이 낸 값은 [DisplayNames.accept] 가 거절한다 */
    fun displayName(raw: String?): String? = DisplayNameRules.sanitize(raw)

    fun locale(raw: String?): String? = raw?.trim()?.takeIf { LOCALE.matches(it) }

    fun timeZone(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }

    const val MAX_DISPLAY_NAME = DisplayNameRules.MAX
}
