package dev.sumin.skeleton.routing.window

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.LocalRouteWindow
import dev.sumin.skeleton.routing.LocalRouteWindowStore
import dev.sumin.skeleton.routing.RequestRoutingAutoConfiguration
import dev.sumin.skeleton.routing.RequestRoutingProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.transaction.support.TransactionOperations

/**
 * 로컬 창 관리 API — `skeleton.routing.enabled=true` + `admin.enabled=true` + 서블릿 웹 + 호출자(Spring Security) + 창 저장소 빈이 모두 있을 때만(꺼져 있으면 경로가 없어 404).
 * 따로 둔 자동 구성이라 시큐리티가 없을 때는 컨트롤러 클래스를 읽지 않는다. 저장소 빈이 없으면 서지 않는다 — 배포 가드(`skeleton.routing.admin.enabled` 에 저장소가 없음)가 이름을 말한다.
 */
@AutoConfiguration(
    after = [RequestRoutingAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.routing.jdbc.RequestRoutingJdbcAutoConfiguration"],
)
@ConditionalOnProperty(prefix = "skeleton.routing", name = ["enabled", "admin.enabled"], havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(LocalRouteWindowStore::class)
class LocalWindowAdminAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun localWindowService(
        store: LocalRouteWindowStore,
        window: LocalRouteWindow,
        audit: OperatorAuditLog,
        tx: TransactionOperations,
        time: TimeProvider,
        properties: RequestRoutingProperties,
    ): LocalWindowService = LocalWindowService(store, window, audit, tx, time, properties.local.maxWindow)

    @Bean
    @ConditionalOnMissingBean
    fun localWindowAdminController(service: LocalWindowService, access: OperatorAccess): LocalWindowAdminController =
        LocalWindowAdminController(service, access)
}
