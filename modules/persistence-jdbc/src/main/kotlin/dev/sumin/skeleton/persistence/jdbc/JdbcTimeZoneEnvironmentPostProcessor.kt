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
 * - `preserveInstants=false`: Timestamp/Date 를 벽시계 리터럴로 다룬다. 이 모듈의 [UtcInstantConversions] 는 JdbcValue 로
 *   JSR-310 값을 직접 넘기므로 영향이 없고, 같은 DataSource 를 쓰는 jOOQ(LocalDateTime→Timestamp 바인딩)·JdbcClient 코드가
 *   JVM 시간대와 무관해진다. 규칙: SQL 파라미터는 `Instant` 가 아니라 UTC `LocalDateTime` 으로 넘긴다
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
                    // Timestamp/Date 파라미터·결과를 시간대 변환 없이 벽시계 그대로. jOOQ 는 LocalDateTime 을 Timestamp 로 바인딩하므로
                    // 이게 없으면 JVM 시간대만큼 밀려 저장된다. persistence-jdbc 쪽은 JdbcValue 리터럴이라 어느 쪽이든 같다
                    "spring.datasource.hikari.data-source-properties.preserveInstants" to "false",
                ),
            ),
        )
    }
}
