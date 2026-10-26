package dev.sumin.skeleton.dashboard

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** 화면 설정: 꺼진 카드 · 타일 키([hidden])와 카드 순서([order], 카드 키만) */
data class DashboardLayout(val hidden: List<String> = emptyList(), val order: List<String> = emptyList())

/**
 * 계정 하나의 화면 설정 읽기 · 쓰기. 저장은 [DashboardLayoutStore](`dashboard-jdbc`) — 없으면 `DASHBOARD.LAYOUT_UNAVAILABLE`(404)이고 프런트는 브라우저 저장으로 물러난다.
 * 계정은 호출자 자신뿐이다 (경로에 계정 id 가 없다). 쓰기는 검증한다: 모르는 키 · 중복 · 너무 많거나 긴 값은 `DASHBOARD.LAYOUT_INVALID`(400).
 * 카드 키 = 고정 키([FIXED_CARDS]) + `rankings.<group>`(자주 쓰는 세 묶음 + 지금 붙은 순위의 묶음 아무거나). `order` 는 카드 키만, `hidden` 은 카드 키 + 지금 붙은 타일 · 순위의 키.
 * 읽기는 저장된 값을 그대로 돌려준다(그 뒤 모듈이 빠져 모르는 키가 남아도 읽는 데는 막지 않는다).
 */
class DashboardLayouts(
    private val store: () -> DashboardLayoutStore?,
    private val itemKeys: () -> Set<String>,
    private val rankingGroups: () -> Set<String>,
) {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun read(accountId: String): DashboardLayout {
        val saved = requireStore().find(accountId) ?: return DashboardLayout()
        return try {
            mapper.readValue(saved, DashboardLayout::class.java)
        } catch (e: Exception) {
            DashboardLayout()   // 읽을 수 없는 줄은 기본값으로 — 다음 저장이 덮는다
        }
    }

    fun save(accountId: String, layout: DashboardLayout): DashboardLayout {
        val target = requireStore()
        validate(layout)
        val json = mapper.writeValueAsString(layout)
        if (json.length > MAX_JSON) throw invalid("too large (at most $MAX_JSON characters)")
        target.save(accountId, json)
        return layout
    }

    private fun requireStore() = store() ?: throw DashboardException(DashboardErrorCode.LAYOUT_UNAVAILABLE, "No dashboard layout store (add dashboard-jdbc)")

    private fun validate(layout: DashboardLayout) {
        val cards = cardKeys()
        if (layout.hidden.size > MAX_KEYS || layout.order.size > MAX_KEYS) throw invalid("too many keys")
        if (layout.hidden.toSet().size != layout.hidden.size || layout.order.toSet().size != layout.order.size) throw invalid("duplicate keys")
        val known = itemKeys() + cards
        layout.hidden.firstOrNull { it.length > MAX_KEY || it !in known }?.let { throw invalid("unknown card or tile key") }
        layout.order.firstOrNull { it !in cards }?.let { throw invalid("order takes card keys only") }
    }

    private fun invalid(detail: String) = DashboardException(DashboardErrorCode.LAYOUT_INVALID, "Invalid screen settings: $detail")

    /** 화면의 카드(구역) 키 — 순서를 바꿀 수 있는 단위. 순위 묶음은 앱이 정하므로 지금 붙은 순위의 묶음을 따른다 */
    fun cardKeys(): Set<String> = FIXED_CARDS + COMMON_GROUPS.map { "rankings.$it" } + rankingGroups().map { "rankings.$it" }

    companion object {
        /** 늘 있는 카드: 타일 · 큰 그래프 · 퍼널 · 앱 통계 · 새/재방문 작은 카드(`ga.returning` 이 붙었을 때만 그려진다) */
        val FIXED_CARDS = setOf("tiles", "chart", "funnel", "app", "returning")
        private val COMMON_GROUPS = listOf("traffic", "pages", "geo")
        const val MAX_JSON = 4000
        private const val MAX_KEYS = 100
        private const val MAX_KEY = 64
    }
}
