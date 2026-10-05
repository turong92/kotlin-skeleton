package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.config.AuthAutoConfiguration
import dev.sumin.skeleton.auth.session.LoginSessionIssuer
import dev.sumin.skeleton.auth.session.SessionRevoker
import dev.sumin.skeleton.auth.sessions.web.SessionController
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 리프레시 토큰 · 세션. 저장소 기본값은 메모리(로컬 · 시험) — 운영은 `auth-session-jdbc` 나 앱의 [SessionStore] 빈으로 바꾼다 (stage · prod 가드가 요구한다).
 * HTTP 는 `skeleton.auth-session.http.enabled=false` 로 끈다.
 */
@AutoConfiguration(after = [AuthAutoConfiguration::class], afterName = ["dev.sumin.skeleton.auth.sessions.jdbc.AuthSessionJdbcAutoConfiguration"])
@EnableConfigurationProperties(AuthSessionProperties::class)
class AuthSessionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SessionStore::class)
    fun inMemorySessionStore(): SessionStore = InMemorySessionStore()

    @Bean
    @ConditionalOnMissingBean
    fun sessionService(store: SessionStore, properties: AuthSessionProperties, time: ObjectProvider<TimeProvider>): SessionService =
        SessionService(store, properties, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean
    fun sessionClients(clientIps: ObjectProvider<ClientIps>): SessionClients = SessionClients(clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean
    fun refreshTokenDelivery(properties: AuthSessionProperties, time: ObjectProvider<TimeProvider>): RefreshTokenDelivery =
        RefreshTokenDelivery(properties, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean(LoginSessionIssuer::class)
    fun sessionLoginIssuer(sessions: SessionService, delivery: RefreshTokenDelivery, clients: SessionClients): LoginSessionIssuer =
        SessionLoginIssuer(sessions, delivery, clients)

    @Bean
    @ConditionalOnMissingBean(SessionRevoker::class)
    fun sessionRevoker(sessions: SessionService): SessionRevoker = SessionRevokerAdapter(sessions)

    @Bean
    @ConditionalOnMissingBean
    fun authSessionDeployGuard(properties: AuthSessionProperties, store: ObjectProvider<SessionStore>): AuthSessionDeployGuard =
        AuthSessionDeployGuard(properties) { store.getIfUnique() }

    @Bean
    @ConditionalOnMissingBean(name = ["authSessionPublicEndpointContributor"])
    @ConditionalOnProperty(prefix = "skeleton.auth-session.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun authSessionPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("POST", "/api/v1/auth/refresh")
            registry.add("POST", "/api/v1/auth/logout")
        }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "skeleton.auth-session.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun sessionController(
        sessions: SessionService,
        delivery: RefreshTokenDelivery,
        clients: SessionClients,
        accounts: AuthAccountRepository,
        tokens: AuthTokenResponseFactory,
    ): SessionController = SessionController(sessions, delivery, clients, accounts, tokens)
}
