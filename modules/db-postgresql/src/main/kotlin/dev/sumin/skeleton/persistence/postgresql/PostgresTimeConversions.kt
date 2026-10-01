package dev.sumin.skeleton.persistence.postgresql

import java.sql.JDBCType
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.mapping.JdbcValue

/** Data JDBC 의 시각 3종을 PG 에서 JVM 시간대와 무관하게 고정. `Instant`=`timestamptz`, `LocalDateTime`=`timestamp`, `LocalDate`=`date`. */
object PostgresTimeConversions {
    @WritingConverter
    object InstantToJdbcValue : Converter<Instant, JdbcValue> {
        override fun convert(source: Instant): JdbcValue =
            JdbcValue.of(source.atOffset(ZoneOffset.UTC), JDBCType.TIMESTAMP_WITH_TIMEZONE)
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
    object OffsetDateTimeToInstant : Converter<OffsetDateTime, Instant> {
        override fun convert(source: OffsetDateTime): Instant = source.toInstant()
    }

    @ReadingConverter
    object TimestampToInstant : Converter<Timestamp, Instant> {
        override fun convert(source: Timestamp): Instant = source.toInstant()
    }

    val all: List<Converter<*, *>> = listOf(
        InstantToJdbcValue, LocalDateTimeToJdbcValue, LocalDateToJdbcValue, OffsetDateTimeToInstant, TimestampToInstant,
    )
}
