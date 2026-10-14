package dev.sumin.skeleton.auth.jwt

import com.nimbusds.jose.jwk.source.ImmutableSecret
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectScoped
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

data class IssuedToken(
    val accessToken: String,
    val expiresAt: Instant,
)

/**
 * 토큰 발급 · 검증. `skeleton.projects` 가 있으면 [perProject] 로 만든다 — 키 · 발급자는 프로젝트마다이고, 토큰에 프로젝트 이름(`prj`)을 넣어
 * 받을 때 지금 프로젝트와 대조한다(키를 같이 써도 A 의 토큰은 B 에서 거절된다). 프로젝트가 없으면 예전 그대로다.
 */
class JwtTokenService private constructor(
    private val keysOf: () -> Keys,
    /** 프로젝트가 있으면 토큰에 프로젝트 이름을 넣고 대조한다 */
    private val projectBound: Boolean,
    private val clock: Clock,
) {
    constructor(properties: AuthProperties.Jwt, clock: Clock = Clock.systemUTC()) : this(Keys(properties, clock).let { k -> { k } }, false, clock)

    private class Keys(val properties: AuthProperties.Jwt, clock: Clock) {
        val secretKey = SecretKeySpec(properties.secret.toByteArray(UTF_8), "HmacSHA256")
        val encoder = NimbusJwtEncoder(ImmutableSecret(secretKey))
        val decoder = NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .apply {
                val timestampValidator = JwtTimestampValidator().apply {
                    setClock(clock)
                }
                setJwtValidator(
                    DelegatingOAuth2TokenValidator(
                        timestampValidator,
                        JwtIssuerValidator(properties.issuer),
                    ),
                )
            }
    }

    companion object {
        const val PROJECT_CLAIM = "prj"

        fun perProject(jwt: ProjectScoped<AuthProperties.Jwt>, clock: Clock = Clock.systemUTC()): JwtTokenService {
            val keys = ProjectScoped { project -> Keys(jwt.of(project), clock) }
            return JwtTokenService({ keys.get() }, true, clock)
        }
    }

    fun issue(principal: CurrentPrincipal): IssuedToken {
        val keys = keysOf()
        val properties = keys.properties
        val now = clock.instant()
        val expiresAt = now.plus(properties.accessTokenTtl)
        val claimsBuilder = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .subject(principal.accountId)
            .claim("roles", principal.roles.toList())
        if (projectBound) claimsBuilder.claim(PROJECT_CLAIM, ProjectContext.current().id)
        principal.username?.let { claimsBuilder.claim("username", it) }
        principal.email?.let { claimsBuilder.claim("email", it) }
        principal.sessionId?.let { claimsBuilder.claim("sid", it) }

        val claims = claimsBuilder.build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = keys.encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        return IssuedToken(accessToken = token, expiresAt = expiresAt)
    }

    fun authenticate(token: String): CurrentPrincipal? = try {
        val keys = keysOf()
        val jwt = keys.decoder.decode(token)
        // 프로젝트가 있으면 토큰의 프로젝트가 지금 프로젝트와 같아야 한다
        if (projectBound && jwt.getClaimAsString(PROJECT_CLAIM) != ProjectContext.current().id) return null
        // Spring Security 7.1: subject 가 nullable — sub 없는 토큰은 인증 실패
        val subject = jwt.subject ?: return null
        CurrentPrincipal(
            accountId = subject,
            username = jwt.getClaimAsString("username"),
            email = jwt.getClaimAsString("email"),
            roles = jwt.getClaimAsStringList("roles")?.toSet() ?: emptySet(),
            sessionId = jwt.getClaimAsString("sid"),
        )
    } catch (_: JwtException) {
        null
    }
}
