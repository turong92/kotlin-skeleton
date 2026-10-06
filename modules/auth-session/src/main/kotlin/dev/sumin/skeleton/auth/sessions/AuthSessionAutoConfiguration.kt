package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.config.AuthAutoConfiguration
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.session.LoginSessionIssuer
import dev.sumin.skeleton.auth.session.SessionEventListener
import dev.sumin.skeleton.auth.session.SessionRevoker
import dev.sumin.skeleton.auth.sessions.web.SessionController
import dev.sumin.skeleton.common.erasure.AccountErasureListener
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
    fun sessionService(
        store: SessionStore,
        properties: AuthSessionProperties,
        time: ObjectProvider<TimeProvider>,
        listeners: ObjectProvider<SessionEventListener>,
        auth: ObjectProvider<AuthProperties>,
        limits: ObjectProvider<dev.sumin.skeleton.common.web.RateLimitStore>,
    ): SessionService = SessionService(
        store, properties, time.getIfAvailable { TimeProvider.systemUtc() },
        // 인스턴스끼리 같은 키 — JWT 비밀에서 용도 접두사를 붙여 만든다 (비밀 자체를 다른 용도에 쓰지 않는다)
        ("session-rotation/" + auth.getIfAvailable { AuthProperties() }.jwt.secret).toByteArray(Charsets.UTF_8),
        rotationLimiter = rotationLimiter(properties.rotation, limits, time),
        onEvent = SessionEventDispatch { listeners.orderedStream().toList() },
    )

    private fun rotationLimiter(
        rotation: AuthSessionProperties.Rotation, limits: ObjectProvider<dev.sumin.skeleton.common.web.RateLimitStore>, time: ObjectProvider<TimeProvider>,
    ): RotationLimiter {
        if (rotation.maxPerWindow == 0) return RotationLimiter.NONE
        val fallback by lazy { dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore() }
        val clock = time.getIfAvailable { TimeProvider.systemUtc() }
        return RotationLimiter { sessionId ->
            val now = clock.now()
            val d = limits.getIfAvailable { fallback }.consume("auth-session:rotate:$sessionId", rotation.maxPerWindow, rotation.window.toMillis(), now)
            if (d.allowed) null else (d.resetAt.epochSecond - now.epochSecond).coerceAtLeast(1)
        }
    }

    /** 기본 듣는 쪽 — 재사용 탐지는 WARN 한 줄(세션 · 계정 id 만, 토큰 없음). 앱이 같은 이름의 빈을 두면 물러난다 */
    @Bean
    @ConditionalOnMissingBean(name = ["loggingSessionEventListener"])
    fun loggingSessionEventListener(): SessionEventListener = LoggingSessionEventListener()

    @Bean
    @ConditionalOnMissingBean
    fun sessionPurge(store: SessionStore, properties: AuthSessionProperties, time: ObjectProvider<TimeProvider>): SessionPurge =
        SessionPurge(store, time.getIfAvailable { TimeProvider.systemUtc() }, properties.purge)

    /** 끝난 세션 행을 주기로 지운다 (`skeleton.auth-session.purge.interval`, 0 이면 끔) */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["authSessionPurgeScheduler"])
    fun authSessionPurgeScheduler(properties: AuthSessionProperties, purge: SessionPurge): SessionPurgeScheduler = SessionPurgeScheduler(properties.purge.interval, purge)

    /** 계정 삭제가 끝나면 그 계정의 세션 행을 지운다 */
    @Bean
    @ConditionalOnMissingBean(name = ["sessionErasureListener"])
    fun sessionErasureListener(store: SessionStore): AccountErasureListener = SessionErasureListener(store)

    @Bean
    @ConditionalOnMissingBean(name = ["refreshDeliveryInfo"])
    fun refreshDeliveryInfo(properties: AuthSessionProperties): dev.sumin.skeleton.auth.session.RefreshDeliveryInfo =
        object : dev.sumin.skeleton.auth.session.RefreshDeliveryInfo { override val mode = properties.delivery.name.lowercase() }

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
    fun authSessionDeployGuard(properties: AuthSessionProperties, auth: ObjectProvider<AuthProperties>, store: ObjectProvider<SessionStore>): AuthSessionDeployGuard =
        AuthSessionDeployGuard(properties, auth.getIfAvailable { AuthProperties() }.protectedProfiles) { store.getIfUnique() }

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
