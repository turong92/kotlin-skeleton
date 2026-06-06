package dev.sumin.skeleton.auth.jwt

import com.nimbusds.jose.jwk.source.ImmutableSecret
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
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

class JwtTokenService(
    private val properties: AuthProperties.Jwt,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val secretKey = SecretKeySpec(properties.secret.toByteArray(UTF_8), "HmacSHA256")
    private val encoder = NimbusJwtEncoder(ImmutableSecret(secretKey))
    private val decoder = NimbusJwtDecoder.withSecretKey(secretKey)
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

    fun issue(principal: CurrentPrincipal): IssuedToken {
        val now = clock.instant()
        val expiresAt = now.plus(properties.accessTokenTtl)
        val claimsBuilder = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .subject(principal.accountId)
            .claim("roles", principal.roles.toList())
        principal.username?.let { claimsBuilder.claim("username", it) }
        principal.email?.let { claimsBuilder.claim("email", it) }

        val claims = claimsBuilder.build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        return IssuedToken(accessToken = token, expiresAt = expiresAt)
    }

    fun authenticate(token: String): CurrentPrincipal? = try {
        val jwt = decoder.decode(token)
        CurrentPrincipal(
            accountId = jwt.subject,
            username = jwt.getClaimAsString("username"),
            email = jwt.getClaimAsString("email"),
            roles = jwt.getClaimAsStringList("roles")?.toSet() ?: emptySet(),
        )
    } catch (_: JwtException) {
        null
    }
}
