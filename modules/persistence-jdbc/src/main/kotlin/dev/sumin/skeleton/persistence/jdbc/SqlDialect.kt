package dev.sumin.skeleton.persistence.jdbc

import java.sql.ResultSet
import java.time.Instant

/**
 * DB 방언마다 달라지는 것만 담는 전략. 구현은 방언 모듈(`modules:db-postgresql`, `modules:db-mysql`)이 하나씩 제공하고,
 * 앱은 그중 하나만 끼운다. 기능 모듈은 이 인터페이스만 안다.
 *
 * 시각 규칙 (실측 2026-10-01, JVM Asia/Seoul): 두 DB 에 공통으로 맞는 바인딩 타입이 없다.
 * PG `timestamptz` 는 UTC `OffsetDateTime` 만 정확하고(UTC `LocalDateTime` 은 −9h, `Instant` 는 드라이버 거부),
 * MySQL `datetime(6)` 은 UTC `LocalDateTime` 만 원문이 UTC 로 남는다. 그래서 JdbcClient / NamedParameterJdbcTemplate 에
 * 시각을 넣을 때는 항상 [instantParam] 을, 꺼낼 때는 [readInstant] 를 쓴다.
 */
interface SqlDialect {
    /** Spring Boot `DatabaseDriver.id` 와 같은 값 — Flyway `{vendor}` 폴더 이름이기도 하다 (`postgresql`, `mysql`). */
    val vendor: String

    fun instantParam(value: Instant?): Any?

    fun readInstant(rs: ResultSet, column: String): Instant?

    /**
     * 충돌(기본 키 · 유니크)하면 아무것도 하지 않는 insert. 파라미터 이름은 칼럼 이름과 같다 (`:recipient_id`).
     * 예외를 잡아 삼키는 방식은 PG 에서 트랜잭션 전체를 abort 시키므로 쓰지 않는다.
     * **돌려주는 갱신 행 수로 넣었는지 판정하지 않는다** — MySQL 구현(`on duplicate key update`)은 이미 있는 행에도 1 을 돌려준다 (실측, alert-jdbc).
     */
    fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String
}
