package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 대시보드(`dashboard` 모듈)가 클래스패스에 있을 때만 신규 가입 타일과 퍼널의 묶음을 기여한다 — `dashboard` 를 컴파일에 끌어오지 않으려고 클래스 이름으로 조건을 건다.
 * 대시보드를 안 붙인 앱에는 아무 빈도 생기지 않는다.
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class, name = ["dev.sumin.skeleton.dashboard.DashboardAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
class AccountDashboardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["accountSignedUpMetric"])
    fun accountSignedUpMetric(dataSource: DataSource, dialect: SqlDialect): SignedUpMetric = SignedUpMetric(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(name = ["accountSignedUpCohort"])
    fun accountSignedUpCohort(dataSource: DataSource, dialect: SqlDialect): SignedUpCohort = SignedUpCohort(NamedParameterJdbcTemplate(dataSource), dialect)
}
