package dev.sumin.skeleton.persistence.jdbc

import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.mapping.JdbcValue
import java.sql.JDBCType
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 시각 3종의 DB 왕복을 **JVM 기본 시간대와 무관**하게 못박는다.
 *
 * 실측(Connector/J 9.x, DriverTimeMatrix):
 * - 읽기: DATETIME/DATE 는 `getObject()` 가 `LocalDateTime`/`LocalDate`(벽시계 리터럴) 을 돌려준다
 * - 쓰기: Data JDBC 기본은 `Instant`→`Timestamp`, `LocalDate`→`java.sql.Date` 로 보내고, 드라이버가 그것을
 *   "instant" 로 취급해 시간대 변환을 한다 → JVM 이 UTC 가 아니면 밀린다 (LocalDate 는 하루가 바뀐다)
 *
 * 그래서 쓰기는 전부 [JdbcValue] 로 JDBC 타입을 명시해 JSR-310 객체를 **그대로** 넘긴다 (리터럴 저장):
 * - `Instant`       → `LocalDateTime`(UTC 벽시계) + TIMESTAMP   … DATETIME 리터럴 = UTC
 * - `LocalDateTime` → 그대로 + TIMESTAMP                          … 벽시계 (ZonedMoment.local)
 * - `LocalDate`     → 그대로 + DATE                               … 달력 날짜, 변환 없음
 * 읽기는 리터럴을 UTC 로 해석해 `Instant` 로 만든다.
 */
object UtcInstantConversions {
    @WritingConverter
    object InstantToJdbcValue : Converter<Instant, JdbcValue> {
        override fun convert(source: Instant): JdbcValue =
            JdbcValue.of(LocalDateTime.ofInstant(source, ZoneOffset.UTC), JDBCType.TIMESTAMP)
    }

    @WritingConverter
    object LocalDateTimeToJdbcValue : Converter<LocalDateTime, JdbcValue> {
        override fun convert(source: LocalDateTime): JdbcValue = JdbcValue.of(source, JDBCType.TIMESTAMP)
    }

    @WritingConverter
    object LocalDateToJdbcValue : Converter<LocalDate, JdbcValue> {
        override fun convert(source: LocalDate): JdbcValue = JdbcValue.of(source, JDBCType.DATE)
    }

    @ReadingConverter
    object LocalDateTimeToInstant : Converter<LocalDateTime, Instant> {
        override fun convert(source: LocalDateTime): Instant = source.toInstant(ZoneOffset.UTC)
    }

    /** 드라이버가 Timestamp 로 돌려주는 경우 (preserveInstants=true 면 이미 올바른 instant) */
    @ReadingConverter
    object TimestampToInstant : Converter<Timestamp, Instant> {
        override fun convert(source: Timestamp): Instant = source.toInstant()
    }

    val all: List<Converter<*, *>> = listOf(
        InstantToJdbcValue, LocalDateTimeToJdbcValue, LocalDateToJdbcValue, LocalDateTimeToInstant, TimestampToInstant,
    )
}
