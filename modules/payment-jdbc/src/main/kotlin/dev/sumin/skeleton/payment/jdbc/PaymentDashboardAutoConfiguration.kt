package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 대시보드(`dashboard` 모듈)가 클래스패스에 있을 때만 순매출 · 결제 건수 타일과 퍼널 단계를 기여한다 — `dashboard` 를 컴파일에 끌어오지 않으려고 클래스 이름으로 조건을 건다 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class, name = ["dev.sumin.skeleton.dashboard.DashboardAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
class PaymentDashboardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["paymentNetRevenueMetric"])
    fun paymentNetRevenueMetric(dataSource: DataSource, dialect: SqlDialect, environment: Environment): NetRevenueMetric =
        NetRevenueMetric(NamedParameterJdbcTemplate(dataSource), dialect, environment.getProperty("skeleton.dashboard.currency", ""))

    @Bean
    @ConditionalOnMissingBean(name = ["paymentPaidCountMetric"])
    fun paymentPaidCountMetric(dataSource: DataSource, dialect: SqlDialect): PaidCountMetric = PaidCountMetric(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(name = ["paymentPaidStep"])
    fun paymentPaidStep(dataSource: DataSource): PaidStep = PaidStep(NamedParameterJdbcTemplate(dataSource))
}
