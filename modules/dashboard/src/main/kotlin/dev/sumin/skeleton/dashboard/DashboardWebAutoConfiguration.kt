package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.dashboard.web.DashboardAdminController
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 관리 API(`/api/v1/admin/dashboard`) — `skeleton.dashboard.admin.enabled=true` 이고 서블릿 웹 앱이며
 * Spring Security(호출자)가 클래스패스에 있을 때만 (없으면 컨트롤러 클래스를 읽지도 않는다 — noOptionalTest 가 증명).
 */
@AutoConfiguration(after = [DashboardAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.dashboard.admin", name = ["enabled"], havingValue = "true")
class DashboardWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun dashboardAdminController(assembler: DashboardAssembler, parser: DashboardRangeParser, access: OperatorAccess): DashboardAdminController =
        DashboardAdminController(assembler, parser, access)
}
