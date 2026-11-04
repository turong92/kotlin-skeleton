package dev.sumin.skeleton.payment.appstore

import com.nimbusds.jose.util.Base64URL
import java.security.MessageDigest
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AppStoreSignatureVerifierTest {
    private val chain = TestChain.create()
    private val verifier = verifier(chain)
    private val payload = """{"hello":"world"}"""

    @Test
    fun `a payload signed by a leaf under the trusted root is accepted`() {
        assertThat(verifier.verify(chain.sign(payload))).isEqualTo(payload)
    }

    @Test
    fun `a chain that ends in another root is rejected`() {
        val other = TestChain.create()
        assertThatThrownBy { verifier.verify(other.sign(payload)) }.isInstanceOf(AppStoreSignatureException::class.java)
    }

    @Test
    fun `an intermediate without Apple's intermediate extension is rejected`() {
        val bad = TestChain.create(intermediateOid = false)
        assertThatThrownBy { verifier(bad).verify(bad.sign(payload)) }.isInstanceOf(AppStoreSignatureException::class.java)
    }

    @Test
    fun `a leaf without Apple's receipt signing extension is rejected`() {
        val bad = TestChain.create(leafOid = false)
        assertThatThrownBy { verifier(bad).verify(bad.sign(payload)) }.isInstanceOf(AppStoreSignatureException::class.java)
    }

    @Test
    fun `a header that is not ES256 is rejected before the chain is read`() {
        val body = Base64URL.encode(payload)
        for (alg in listOf("none", "ES384", "HS256", "RS256")) {
            val header = Base64URL.encode("""{"alg":"$alg","x5c":${chain.x5c().joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}}""")
            assertThatThrownBy { verifier.verify("$header.$body.${Base64URL.encode(ByteArray(64))}") }.`as`(alg).isInstanceOf(AppStoreSignatureException::class.java)
        }
    }

    @Test
    fun `a changed payload breaks the signature`() {
        val parts = chain.sign(payload).split('.')
        val forged = "${parts[0]}.${Base64URL.encode("""{"hello":"forged"}""")}.${parts[2]}"
        assertThatThrownBy { verifier.verify(forged) }.isInstanceOf(AppStoreSignatureException::class.java)
    }

    @Test
    fun `a chain that is not exactly leaf, intermediate and root is rejected`() {
        assertThatThrownBy { verifier.verify(chain.sign(payload, chain.x5c(chain.leaf, chain.intermediate))) }.isInstanceOf(AppStoreSignatureException::class.java)
        assertThatThrownBy { verifier.verify(chain.sign(payload, chain.x5c(chain.leaf))) }.isInstanceOf(AppStoreSignatureException::class.java)
    }

    @Test
    fun `an expired chain is rejected`() {
        val late = verifier(chain, now = NOW.plus(Duration.ofDays(400)))
        assertThatThrownBy { late.verify(chain.sign(payload)) }.isInstanceOf(AppStoreSignatureException::class.java)
    }

    @Test
    fun `a token that is not a JWS is rejected`() {
        for (garbage in listOf("", "a.b", "not a jws", "a.b.c")) {
            assertThatThrownBy { verifier.verify(garbage) }.`as`(garbage).isInstanceOf(AppStoreSignatureException::class.java)
        }
    }

    @Test
    fun `the bundled root is Apple Root CA - G3`() {
        val root = AppStoreSignatureVerifier.appleRootCaG3()
        assertThat(root.subjectX500Principal.name).contains("CN=Apple Root CA - G3")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02X".format(it) }
        assertThat(sha256).isEqualTo("63343ABFB89A6A03EBB57E9B3F5FA7BE7C4F5C756F3017B3A8C488C3653E9179")
    }
}
