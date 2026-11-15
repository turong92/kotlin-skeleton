package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.web.RouteInboundFilter
import dev.sumin.skeleton.routing.web.RoutingErrorWriter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper

/**
 * 요청 라우팅 — 꺼짐이 기본(`skeleton.routing.enabled=false`)이고, 꺼지면 빈이 하나도 없다.
 * 창 저장소는 `request-routing-jdbc` 가 낸다(없으면 창은 늘 닫혀 있다).
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.routing.jdbc.RequestRoutingJdbcAutoConfiguration"])
@ConditionalOnProperty("skeleton.routing.enabled", havingValue = "true")
@EnableConfigurationProperties(RequestRoutingProperties::class)
class RequestRoutingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun routeTargets(properties: RequestRoutingProperties): RouteTargets = RouteTargets(properties)

    @Bean
    @ConditionalOnBean(LocalRouteWindowStore::class)
    @ConditionalOnMissingBean(LocalRouteWindow::class)
    fun storeLocalRouteWindow(
        store: LocalRouteWindowStore,
        properties: RequestRoutingProperties,
        time: TimeProvider,
    ): LocalRouteWindow = StoreLocalRouteWindow(store, properties.local.cacheTtl, time)

    @Bean
    @ConditionalOnMissingBean(LocalRouteWindow::class)
    fun closedLocalRouteWindow(): LocalRouteWindow = ClosedLocalRouteWindow

    @Bean
    @ConditionalOnMissingBean(name = ["requestRoutingPermissionDeclaration"])
    fun requestRoutingPermissionDeclaration(): PermissionDeclaration = RoutingPermissions.declaration

    /** 보안 필터 체인(Spring Boot 기본 순서 -100) 바로 뒤 — 인증된 호출자를 알고 난 다음에 표시를 믿는다 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = ["routeInboundFilterRegistration"])
    fun routeInboundFilterRegistration(
        properties: RequestRoutingProperties,
        targets: RouteTargets,
        window: LocalRouteWindow,
        access: OperatorAccess,
        time: TimeProvider,
        objectMapper: ObjectMapper,
    ): FilterRegistrationBean<RouteInboundFilter> =
        FilterRegistrationBean(RouteInboundFilter(properties, targets, window, access, time, RoutingErrorWriter(objectMapper))).apply {
            order = SECURITY_CHAIN_ORDER + 10
        }

    private companion object {
        const val SECURITY_CHAIN_ORDER = -100
    }
}
