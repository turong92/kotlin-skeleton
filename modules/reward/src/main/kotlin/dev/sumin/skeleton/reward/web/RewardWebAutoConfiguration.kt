package dev.sumin.skeleton.reward.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.reward.RewardAutoConfiguration
import dev.sumin.skeleton.reward.RewardGrantService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 지급 건 운영자 경로 - 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 `skeleton.reward.admin.enabled=true` 일 때만(기본 꺼짐 → 404).
 */
@AutoConfiguration(after = [RewardAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.reward.admin", name = ["enabled"], havingValue = "true")
class RewardWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(RewardGrantService::class)
    @ConditionalOnMissingBean
    fun rewardAdminController(
        service: RewardGrantService,
        access: OperatorAccess,
        audit: OperatorAuditLog,
        issuers: ObjectProvider<RewardIssuer>,
    ): RewardAdminController = RewardAdminController(service, access, audit) { issuers.orderedStream().toList() }
}
