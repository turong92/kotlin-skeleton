package dev.sumin.skeleton.auth.sessions.jdbc

import dev.sumin.skeleton.auth.sessions.ActivityZone
import javax.sql.DataSource
import java.time.ZoneId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
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
    fun authSessionReturnedStep(dataSource: DataSource, zone: ObjectProvider<ActivityZone>, environment: Environment): ReturnedStep {
        // `auth-session` 의 자동 설정을 끈 조립(워크벤치 …)에는 활동 시간대 빈이 없다 — 그때는 앱 시간대로 센다(기록이 없으니 어차피 0 이다)
        val fallback = ZoneId.of(environment.getProperty("skeleton.time.default-zone", "Asia/Seoul"))
        return ReturnedStep(NamedParameterJdbcTemplate(dataSource)) { zone.getIfAvailable()?.zone() ?: fallback }
    }
}
