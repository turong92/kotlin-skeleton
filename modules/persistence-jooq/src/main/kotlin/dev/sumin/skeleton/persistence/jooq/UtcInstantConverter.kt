package dev.sumin.skeleton.persistence.jooq

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.jooq.impl.AbstractConverter

/**
 * DATETIME(벽시계 리터럴) ↔ Instant 를 **UTC 로 고정**. 세션이 UTC([JooqTimeZoneEnvironmentPostProcessor]) 이므로
 * JVM 기본 시간대와 무관하다. 코드 생성의 forcedType converter 로 쓴다 (build.gradle.kts 참고).
 */
class UtcInstantConverter : AbstractConverter<LocalDateTime, Instant>(LocalDateTime::class.java, Instant::class.java) {
    override fun from(databaseObject: LocalDateTime?): Instant? = databaseObject?.toInstant(ZoneOffset.UTC)
    override fun to(userObject: Instant?): LocalDateTime? = userObject?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
}
