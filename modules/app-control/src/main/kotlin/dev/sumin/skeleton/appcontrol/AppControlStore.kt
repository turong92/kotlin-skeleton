package dev.sumin.skeleton.appcontrol

/**
 * 저장소 포트(구현: `app-control-jdbc`). 표 하나 · 읽기는 전체 한 번 - 캐시가 통째로 바꿔 끼운다.
 * [transaction] 안의 [put] · [delete] 와 운영 기록은 한 트랜잭션으로 묶인다.
 */
interface AppControlStore {
    fun <T> transaction(block: () -> T): T

    fun all(): List<AppControlEntry>

    /** 한 줄(없으면 null) */
    fun find(name: String): AppControlEntry?

    /** 없으면 만들고 있으면 덮어쓴다 (마지막 쓴 것이 이긴다) */
    fun put(entry: AppControlEntry)

    /** 지웠으면 true */
    fun delete(name: String): Boolean
}
