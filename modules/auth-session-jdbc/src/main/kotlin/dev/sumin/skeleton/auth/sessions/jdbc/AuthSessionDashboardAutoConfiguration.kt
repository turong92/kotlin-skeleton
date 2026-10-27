package dev.sumin.skeleton.auth.sessions.jdbc

import dev.sumin.skeleton.auth.sessions.ActivityZone
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 대시보드(`dashboard` 모듈)가 클래스패스에 있을 때만 활성 이용자 타일과 퍼널 단계를 기여한다 — `dashboard` 를 컴파일에 끌어오지 않으려고 클래스 이름으로 조건을 건다 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class, name = ["dev.sumin.skeleton.dashboard.DashboardAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
class AuthSessionDashboardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["authSessionActiveUsersMetric"])
    fun authSessionActiveUsersMetric(dataSource: DataSource): ActiveUsersMetric = ActiveUsersMetric(NamedParameterJdbcTemplate(dataSource))

    @Bean
    @ConditionalOnMissingBean(name = ["authSessionReturnedStep"])
    fun authSessionReturnedStep(dataSource: DataSource, zone: ActivityZone): ReturnedStep = ReturnedStep(NamedParameterJdbcTemplate(dataSource), zone::zone)
}
