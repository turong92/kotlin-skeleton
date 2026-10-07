package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.authorprofile.AuthorProfileAutoConfiguration
import dev.sumin.skeleton.authorprofile.AuthorProfileProperties
import dev.sumin.skeleton.authorprofile.AuthorProfileService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 작성자 프로필 HTTP(`skeleton.author-profile.http.base-path`, 기본 `/api/v1/author-profiles`) — 서블릿 웹 앱이고 Spring Security(호출자)가 있을 때만.
 * `skeleton.author-profile.http.enabled=false` 로 끈다. 인증은 앱의 보안 설정이 건다 (auth 의 기본 체인은 모든 경로에 인증을 요구한다) —
 * 이 경로들은 **공개 경로가 아니다**, [dev.sumin.skeleton.common.web.PublicEndpointContributor] 를 내지 않는다.
 */
@AutoConfiguration(after = [AuthorProfileAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.author-profile.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AuthorProfileWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun authorProfileCallers(): AuthorProfileCallers = AuthorProfileCallers()

    @Bean
    @ConditionalOnMissingBean
    fun authorProfileController(service: AuthorProfileService, callers: AuthorProfileCallers, properties: AuthorProfileProperties): AuthorProfileController =
        AuthorProfileController(service, callers, properties)
}
