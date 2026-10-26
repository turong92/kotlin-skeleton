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
    /** (day, source, kind, dimension) 이 같으면 덮어쓴다 — 같은 날을 두 번 적재해도 같은 행 */
    fun upsert(rows: List<DashboardDailyRow>)

    fun read(source: String, kind: String, fromDay: LocalDate, toDay: LocalDate): List<DashboardDailyRow>

    /** 이 출처를 마지막으로 적재한 시각. 한 번도 없으면 null */
    fun lastIngested(source: String): Instant?
}

/** 외부 출처 하나의 하루치를 읽어 일별 행으로 만든다. 적재 작업이 새벽마다 부른다 */
interface DashboardIngestor {
    val source: String

    fun ingest(day: LocalDate, zone: ZoneId): List<DashboardDailyRow>
}

/** 계정별 화면 설정(JSON) 한 줄 */
interface DashboardLayoutStore {
    fun find(accountId: String): String?

    fun save(accountId: String, json: String)

    fun delete(accountId: String)
}
