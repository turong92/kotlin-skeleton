package dev.sumin.skeleton.persistence.jooq

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.jooq.impl.AbstractConverter

/**
 * MySQL 생성 경로 전용(-Pskeleton.jooq.dialect=mysql): DATETIME(벽시계 리터럴) ↔ Instant 를 **UTC 로 고정**.
 * 세션 UTC 는 db-mysql 의 MySqlTimeZoneEnvironmentPostProcessor. PG 생성 경로는 timestamptz 를 jOOQ INSTANT 로 만들어 이 변환기를 안 쓴다.
 */
class UtcInstantConverter : AbstractConverter<LocalDateTime, Instant>(LocalDateTime::class.java, Instant::class.java) {
    override fun from(databaseObject: LocalDateTime?): Instant? = databaseObject?.toInstant(ZoneOffset.UTC)
    override fun to(userObject: Instant?): LocalDateTime? = userObject?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
}
