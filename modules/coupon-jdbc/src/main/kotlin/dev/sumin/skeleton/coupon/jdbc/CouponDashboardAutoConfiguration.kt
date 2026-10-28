package dev.sumin.skeleton.coupon.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 대시보드(`dashboard` 모듈)가 클래스패스에 있고 앱이 `skeleton.coupon.dashboard.enabled=true` 로 켰을 때만 쿠폰 사용 수 타일과 많이 쓰인 쿠폰 순위를 기여한다.
 * `dashboard` 를 컴파일에 끌어오지 않으려고 클래스 이름으로 조건을 건다(`payment-jdbc` 와 같다). 기본은 꺼짐 - 이 모듈을 붙였다고 대시보드가 바뀌지 않는다.
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class, name = ["dev.sumin.skeleton.dashboard.DashboardAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(prefix = "skeleton.coupon.dashboard", name = ["enabled"], havingValue = "true")
class CouponDashboardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["couponUsedMetric"])
    fun couponUsedMetric(dataSource: DataSource, dialect: SqlDialect): CouponUsedMetric = CouponUsedMetric(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(name = ["couponTopUsedRanking"])
    fun couponTopUsedRanking(dataSource: DataSource, dialect: SqlDialect): CouponTopUsedRanking = CouponTopUsedRanking(NamedParameterJdbcTemplate(dataSource), dialect)
}
