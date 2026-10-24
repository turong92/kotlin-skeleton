package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.AccountEventPublisher
import dev.sumin.skeleton.account.web.AccountCallers
import dev.sumin.skeleton.account.web.AccountWebAutoConfiguration
import dev.sumin.skeleton.authorprofile.AuthorLinkLookup
import dev.sumin.skeleton.authorprofile.AuthorProfileAdminDeployGuard
import dev.sumin.skeleton.authorprofile.AuthorProfileAutoConfiguration
import dev.sumin.skeleton.authorprofile.AuthorProfileRepository
import dev.sumin.skeleton.common.authz.OperatorAccess
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 관리자의 연결 조회 — `skeleton.author-profile.admin.enabled=true`(기본 꺼짐)일 때만 서블릿 웹 앱 + Spring Security 에서 등록된다.
 * 사용자용 HTTP(`http.enabled`)와 따로 켜고 끈다. 호출자 판정은 `account` 의 [AccountCallers.requirePermission] 으로 권한 `author-profile.read` 이고
 * (공용 검사기 `OperatorAccess` — `account` 가 있으면 매 호출 저장소의 계정), `account` 의 HTTP 가 꺼져 그 빈이 없으면 같은 규칙으로 직접 만든다.
 */
@AutoConfiguration(after = [AuthorProfileAutoConfiguration::class, AccountAutoConfiguration::class, AccountWebAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.author-profile.admin", name = ["enabled"], havingValue = "true")
class AuthorProfileAdminWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun authorLinkLookup(repository: AuthorProfileRepository, accounts: ObjectProvider<AccountRepository>, events: AccountEventPublisher): AuthorLinkLookup =
        AuthorLinkLookup(repository, { accounts.getObject() }, events)

    @Bean
    @ConditionalOnMissingBean
    fun authorProfileAdminController(
        callers: ObjectProvider<AccountCallers>,
        access: ObjectProvider<OperatorAccess>,
        lookup: AuthorLinkLookup,
    ): AuthorProfileAdminController =
        AuthorProfileAdminController(callers.getIfAvailable { AccountCallers { access.getObject() } }, lookup)

    /** 조회 감사가 로그 한 줄에만 남는 조립(stage · prod)을 알린다 */
    @Bean
    @ConditionalOnMissingBean
    fun authorProfileAdminDeployGuard(listeners: ObjectProvider<AccountEventListener>): AuthorProfileAdminDeployGuard =
        AuthorProfileAdminDeployGuard { listeners.orderedStream().toList() }
}
