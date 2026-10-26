package dev.sumin.skeleton.dashboard

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 저장 포트 — `dashboard-jdbc` 가 구현한다. 이 모듈에는 메모리 구현이 없다 (없으면 지난 기간의 외부 숫자와 화면 설정이 없을 뿐 대시보드는 돈다).

/** 일별 집계 한 줄. 전체 값은 [dimension] 이 빈 문자열. 집계 숫자만 — 경로는 쿼리 문자열을 떼고, 개인을 알아볼 수 있는 값은 담지 않는다 */
data class DashboardDailyRow(
    val day: LocalDate,
    val source: String,
    val kind: String,
    val dimension: String,
    val value: Long,
)

interface DashboardDailyStore {
    /**
     * 넘긴 [rows] 가 [day] · [source] 의 [kinds] 의 전부가 된다 — 같은 날을 다시 적재하면 같은 행이고, 이번 결과에서 빠진 차원의 옛 줄은 지워진다.
     * **[kinds] 에 든 종류는 이번 결과에 줄이 하나도 없어도 옛 줄을 지운다**(통째로 빈 종류). 한 트랜잭션. [rows] 에 나온 종류는 [kinds] 에 없어도 같이 다룬다
     */
    fun replace(day: LocalDate, source: String, kinds: Collection<String>, rows: List<DashboardDailyRow>)

    /** 행에 나온 (날짜 · 출처)마다 그 행들의 종류만 [replace] 한다 — 빈 종류를 비우려면 [replace] 를 직접 부른다 */
    fun upsert(rows: List<DashboardDailyRow>) {
        rows.groupBy { it.day to it.source }.forEach { (key, group) -> replace(key.first, key.second, emptySet(), group) }
    }

    fun read(source: String, kind: String, fromDay: LocalDate, toDay: LocalDate): List<DashboardDailyRow>

    /** 이 출처를 마지막으로 적재한 시각. 한 번도 없으면 null */
    fun lastIngested(source: String): Instant?
}

/** 외부 출처 하나의 하루치를 읽어 일별 행으로 만든다. 적재 작업이 새벽마다 부른다 */
interface DashboardIngestor {
    val source: String

    /** 이 적재기가 하루에 채우는 종류 전부 — 이번에 한 줄도 없는 종류의 옛 줄도 지우려고 알린다 */
    val kinds: Set<String> get() = emptySet()

    /** 지금(프로젝트 문맥 안에서) 적재할 수 있는가 — 키가 비면 false, 이때는 아무것도 부르지도 지우지도 않는다 */
    fun active(): Boolean = true

    fun ingest(day: LocalDate, zone: ZoneId): List<DashboardDailyRow>
}

/** 계정별 화면 설정(JSON) 한 줄 */
interface DashboardLayoutStore {
    fun find(accountId: String): String?

    fun save(accountId: String, json: String)

    fun delete(accountId: String)
}
