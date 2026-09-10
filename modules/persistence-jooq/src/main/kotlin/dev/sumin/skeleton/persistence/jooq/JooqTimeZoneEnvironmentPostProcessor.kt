package dev.sumin.skeleton.persistence.jooq

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/** persistence-jdbc 와 같은 규약: DB 세션을 UTC 로 강제 (Hikari 드라이버 속성). 앱 yml 이 같은 키를 쓰면 앱이 이긴다. */
class JooqTimeZoneEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        environment.propertySources.addLast(
            MapPropertySource(
                "skeleton-persistence-jooq-defaults",
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
