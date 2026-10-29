package dev.sumin.skeleton.badge.web

import dev.sumin.skeleton.badge.BadgeAutoConfiguration
import dev.sumin.skeleton.badge.BadgeAwards
import dev.sumin.skeleton.badge.BadgeCatalog
import dev.sumin.skeleton.badge.BadgeDefinitions
import dev.sumin.skeleton.badge.BadgeProperties
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 사용자 경로(내 배지 · 프로필 배지, `skeleton.badge.http.enabled` 기본 켜짐)와 운영자 경로(`skeleton.badge.admin.enabled=true` 일 때만, 꺼져 있으면 404).
 * 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 */
@AutoConfiguration(after = [BadgeAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class BadgeWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(BadgeCatalog::class)
    @ConditionalOnProperty(prefix = "skeleton.badge.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun badgeController(catalog: BadgeCatalog, properties: BadgeProperties): BadgeController = BadgeController(catalog, properties)

    /**
     * 프로필 배지 한 경로만 로그인 없이 연다(프로필 경로 `GET {base}/accounts/{id}` 하나이고 내 배지 `me` 는 열리지 않는다). 기본은 닫혀 있고 `skeleton.badge.http.allow-anonymous-read=true` 로 연다(앱의 선택).
     * 앱은 같은 빈 이름으로 덮어쓸 수 있다.
     */
    @Bean
    @ConditionalOnBean(BadgeCatalog::class)
    @ConditionalOnMissingBean(name = ["badgePublicEndpointContributor"])
    @ConditionalOnProperty(prefix = "skeleton.badge.http", name = ["allow-anonymous-read"], havingValue = "true")
    fun badgePublicEndpointContributor(properties: BadgeProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> if (properties.http.enabled) registry.add("GET", "${properties.http.basePath}/accounts/*") }

    @Bean
    @ConditionalOnBean(BadgeDefinitions::class, BadgeAwards::class)
    @ConditionalOnProperty(prefix = "skeleton.badge.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun badgeAdminController(
        definitions: BadgeDefinitions,
        awards: BadgeAwards,
        access: OperatorAccess,
        audit: OperatorAuditLog,
        labels: ObjectProvider<AccountLabels>,
    ): BadgeAdminController = BadgeAdminController(definitions, awards, access, audit, labels.getIfAvailable())
}
