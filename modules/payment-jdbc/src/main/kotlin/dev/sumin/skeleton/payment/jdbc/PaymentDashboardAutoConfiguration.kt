package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentGateway
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 대시보드(`dashboard` 모듈)가 클래스패스에 있을 때만 순매출 · 결제 건수 타일과 퍼널 단계를 기여한다 — `dashboard` 를 컴파일에 끌어오지 않으려고 클래스 이름으로 조건을 건다 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class, name = ["dev.sumin.skeleton.dashboard.DashboardAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
class PaymentDashboardAutoConfiguration {
    /** 지금 끼워 둔 결제사 키의 모드 — 부를 때마다 요청의 프로젝트 라우터에서 읽는다 */
    @Bean
    @ConditionalOnMissingBean
    fun currentModeOrders(router: ObjectProvider<PaymentProviderRouter>): CurrentModeOrders =
        CurrentModeOrders { router.getIfAvailable()?.gateways(PaymentGateway::class)?.map { it.providerId to it.mode.name }.orEmpty() }

    @Bean
    @ConditionalOnMissingBean(name = ["paymentNetRevenueMetric"])
    fun paymentNetRevenueMetric(dataSource: DataSource, dialect: SqlDialect, orders: CurrentModeOrders): NetRevenueMetric =
        NetRevenueMetric(NamedParameterJdbcTemplate(dataSource), dialect, orders)

    @Bean
    @ConditionalOnMissingBean(name = ["paymentPaidCountMetric"])
    fun paymentPaidCountMetric(dataSource: DataSource, dialect: SqlDialect, orders: CurrentModeOrders): PaidCountMetric =
        PaidCountMetric(NamedParameterJdbcTemplate(dataSource), dialect, orders)

    @Bean
    @ConditionalOnMissingBean(name = ["paymentPaidStep"])
    fun paymentPaidStep(dataSource: DataSource, orders: CurrentModeOrders): PaidStep = PaidStep(NamedParameterJdbcTemplate(dataSource), orders)
}
