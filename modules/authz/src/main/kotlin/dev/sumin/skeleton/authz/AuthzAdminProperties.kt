package dev.sumin.skeleton.authz

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.authz.admin")
data class AuthzAdminProperties(
    /** 역할 관리 API(`/api/v1/admin/roles` · `/api/v1/admin/permissions`). 꺼져 있으면 404 */
    val enabled: Boolean = false,
)
