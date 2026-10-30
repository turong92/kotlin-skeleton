package dev.sumin.skeleton.search

import dev.sumin.skeleton.common.search.SearchSource

/** 운영 - 종류별 문서 수 · 재색인 상태와 재색인 시작. 소스 목록은 부를 때마다 읽는다(소스 빈이 늦게 서도 된다) */
class SearchAdmin(
    private val engine: SearchEngine,
    private val rebuilds: SearchRebuilds,
    private val sources: () -> List<SearchSource>,
) {
    fun types(): List<SearchTypeStatus> {
        val counts = engine.countByType()
        val states = rebuilds.states()
        return (counts.keys + sources().map { it.type }).toSortedSet().map { type ->
            SearchTypeStatus(type, counts[type] ?: 0, states[type]?.running ?: false, states[type]?.lastRebuiltAt)
        }
    }

    fun rebuild(type: String) {
        if (sources().none { it.type == type }) throw SearchException(SearchErrorCode.SOURCE_NOT_FOUND, "No search source for type: $type")
        if (!rebuilds.start(type)) throw SearchException(SearchErrorCode.REBUILD_RUNNING, "A rebuild of $type is already running")
    }
}
