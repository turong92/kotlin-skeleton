package dev.sumin.skeleton.search.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.search.SearchAdmin
import dev.sumin.skeleton.search.SearchAutoConfiguration
import dev.sumin.skeleton.search.SearchProperties
import dev.sumin.skeleton.search.SearchReader
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 검색 경로(`skeleton.search.http.enabled` 기본 켜짐)와 운영자 경로(`skeleton.search.admin.enabled=true` 일 때만, 꺼져 있으면 404).
 * 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 */
@AutoConfiguration(after = [SearchAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class SearchWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(SearchReader::class)
    @ConditionalOnProperty(prefix = "skeleton.search.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun searchController(reader: SearchReader, properties: SearchProperties): SearchController = SearchController(reader, properties)

    /**
     * 검색 경로 하나만 로그인 없이 연다. 기본은 닫혀 있고 `skeleton.search.http.allow-anonymous-read=true` 로 연다(앱의 선택).
     * 앱은 같은 빈 이름으로 덮어쓸 수 있다.
     */
    @Bean
    @ConditionalOnBean(SearchReader::class)
    @ConditionalOnMissingBean(name = ["searchPublicEndpointContributor"])
    @ConditionalOnProperty(prefix = "skeleton.search.http", name = ["allow-anonymous-read"], havingValue = "true")
    fun searchPublicEndpointContributor(properties: SearchProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> if (properties.http.enabled) registry.add("GET", properties.http.basePath) }

    @Bean
    @ConditionalOnBean(SearchAdmin::class)
    @ConditionalOnProperty(prefix = "skeleton.search.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun searchAdminController(admin: SearchAdmin, access: OperatorAccess, audit: OperatorAuditLog): SearchAdminController =
        SearchAdminController(admin, access, audit)
}
