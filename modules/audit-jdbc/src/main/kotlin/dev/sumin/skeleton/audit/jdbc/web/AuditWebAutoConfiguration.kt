package dev.sumin.skeleton.audit.jdbc.web

import dev.sumin.skeleton.audit.jdbc.AuditJdbcAutoConfiguration
import dev.sumin.skeleton.audit.jdbc.AuditLogStore
import dev.sumin.skeleton.audit.jdbc.AuditOrigin
import dev.sumin.skeleton.audit.jdbc.RequestOrigin
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.web.ClientIps
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** 서블릿 웹 앱에서만 — 기록에 접속 IP · 기기 정보를 싣고, 켰을 때(`skeleton.audit.admin.enabled=true`) 운영자 조회 경로를 연다 */
@AutoConfiguration(after = [AuditJdbcAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class AuditWebAutoConfiguration {
    /** 지금 처리 중인 요청의 출처 — 요청 밖(작업 · 시작 때)이면 null. IP 는 플랫폼의 클라이언트 IP 규칙(`skeleton.web.client-ip`)을 따른다 */
    @Bean
    @ConditionalOnMissingBean(AuditOrigin::class)
    fun auditOrigin(clientIps: ObjectProvider<ClientIps>): AuditOrigin {
        val ips = clientIps.getIfAvailable { ClientIps() }
        return AuditOrigin {
            val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            request?.let { RequestOrigin(ips.of(it).ip, it.getHeader("User-Agent")) }
        }
    }

    @Bean
    @ConditionalOnBean(AuditLogStore::class)
    @ConditionalOnProperty(prefix = "skeleton.audit.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun auditAdminController(store: AuditLogStore, access: OperatorAccess): AuditAdminController = AuditAdminController(store, access)
}
