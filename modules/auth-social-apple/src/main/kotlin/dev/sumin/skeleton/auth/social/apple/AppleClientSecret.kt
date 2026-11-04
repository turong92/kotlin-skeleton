package dev.sumin.skeleton.auth.social.apple

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Date

/**
 * 애플 토큰 엔드포인트에 보내는 `client_secret` — 우리 `.p8` 키로 서명한 짧은 ES256 JWT (`kid`=key-id, `iss`=team-id, `sub`=client-id, `aud`=애플).
 * 부를 때마다 새로 만든다(수명 5분, 캐시 없음) — 6개월 만료를 관리할 일이 없다. 키는 만들 때 한 번 읽는다: 틀리면 [IllegalArgumentException] 이고 메시지에 키 내용은 없다.
 */
internal class AppleClientSecret(
    private val teamId: String,
    private val keyId: String,
    private val clientId: String,
    private val audience: String,
    privateKeyPem: String,
    private val clock: Clock,
) {
    private val signer: ECDSASigner = signerOf(privateKeyPem)

    fun issue(): String {
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .issuer(teamId).subject(clientId).audience(audience)
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(LIFETIME)))
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims).also { it.sign(signer) }.serialize()
    }

    private companion object {
        val LIFETIME: Duration = Duration.ofMinutes(5)
        const val SETTING = "skeleton.auth-social-apple.private-key"

        fun signerOf(pem: String): ECDSASigner {
            // 원인 예외를 달지 않는다 — 파서의 메시지에 키 조각이 실릴 수 있다
            val key = try {
                // 환경변수는 줄바꿈을 `\n` 두 글자로 주기도 한다
                val body = pem.replace("\\n", "\n").lines().filterNot { it.trim().startsWith("-----") }.joinToString("") { it.trim() }
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(body))) as ECPrivateKey
            } catch (ex: GeneralSecurityException) {
                throw invalid()
            } catch (ex: IllegalArgumentException) {
                throw invalid()
            } catch (ex: ClassCastException) {
                throw invalid()
            }
            val signer = try {
                ECDSASigner(key)
            } catch (ex: JOSEException) {
                throw invalid()
            }
            require(JWSAlgorithm.ES256 in signer.supportedJWSAlgorithms()) { "$SETTING must be a P-256 (prime256v1) key: Apple signs Sign in with Apple keys with ES256" }
            return signer
        }

        fun invalid() = IllegalArgumentException("$SETTING is not a PKCS#8 PEM elliptic-curve private key: put the whole content of the AuthKey_<key id>.p8 file here")
    }
}
