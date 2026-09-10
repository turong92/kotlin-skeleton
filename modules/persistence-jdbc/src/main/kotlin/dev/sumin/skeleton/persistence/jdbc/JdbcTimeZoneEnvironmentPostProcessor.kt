package dev.sumin.skeleton.persistence.jdbc

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * DB 세션 시간대를 UTC 로 강제한다. JDBC URL 에 파라미터를 안 적어도 된다.
 *
 * MySQL Connector/J 속성을 Hikari `data-source-properties` 로 넘긴다:
 * - `connectionTimeZone=UTC` : 드라이버가 DATETIME 을 UTC 로 읽고 쓴다 (JVM 기본 시간대와 무관)
 * - `forceConnectionTimeZoneToSession=true` : DB 세션 `time_zone` 도 UTC (`NOW()` 등 SQL 쪽 시각도 UTC)
 * - `preserveInstants` 는 드라이버 기본(true) 유지: `Timestamp`(=Instant) 파라미터가 UTC 벽시계 리터럴로 저장된다.
 *   실측(DriverTimeMatrix): false 로 하면 JVM 시간대 벽시계로 저장돼 JVM 이 UTC 가 아니면 밀린다
 *
 * 최하위 우선순위 property source 라 앱이 같은 키를 선언하면 앱이 이긴다.
 */
class JdbcTimeZoneEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        environment.propertySources.addLast(
            MapPropertySource(
                "skeleton-persistence-jdbc-defaults",
                mapOf(
                    "spring.datasource.hikari.data-source-properties.connectionTimeZone" to "UTC",
                    "spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession" to "true",
                ),
            ),
        )
    }
}
