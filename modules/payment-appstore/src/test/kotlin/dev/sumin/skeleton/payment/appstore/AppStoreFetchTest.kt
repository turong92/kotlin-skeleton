package dev.sumin.skeleton.payment.appstore

import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PushedSubscriptionStatus
import dev.sumin.skeleton.payment.StoreClaim
import dev.sumin.skeleton.payment.StorePurchaseKind
import dev.sumin.skeleton.payment.StorePurchaseState
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class AppStoreFetchTest {
    private val chain = TestChain.create()
    private val prod = FakeAppStoreServer(chain)
    private val sandbox = FakeAppStoreServer(chain, "Sandbox")
    private val key = TestApiKey()
    private val gateway = provider(chain, prod, sandbox, key)
    private val claim = StoreClaim("2000000000000001")

    @AfterEach
    fun stop() {
        prod.close()
        sandbox.close()
    }

    @Test
    fun `a production purchase is read from the production host and keeps Apple's facts`() {
        prod.knows("2000000000000001", transactionJson())
        val purchase = gateway.fetch(claim)!!
        assertThat(purchase.mode).isEqualTo(PaymentMode.LIVE)
        assertThat(purchase.kind).isEqualTo(StorePurchaseKind.ONE_TIME)
        assertThat(purchase.state).isEqualTo(StorePurchaseState.PURCHASED)
        assertThat(purchase.productId).isEqualTo("com.example.sample.cheer3000")
        assertThat(purchase.providerPaymentId).isEqualTo("2000000000000001")
        assertThat(purchase.accountToken).isEqualTo("6f1c2a3e-0000-4000-8000-000000000001")
        assertThat(purchase.amount).isEqualTo(PaymentAmount(4990, "KRW"))
        assertThat(purchase.paidAt).isEqualTo(NOW.minusSeconds(3600))
        assertThat(sandbox.requests).isEmpty()
    }

    @Test
    fun `a purchase the production host does not know is asked of the sandbox and is a test purchase`() {
        sandbox.knows("2000000000000001", transactionJson(environment = "Sandbox"))
        val purchase = gateway.fetch(claim)!!
        assertThat(purchase.mode).isEqualTo(PaymentMode.TEST)
        assertThat(prod.requests.map { it.path }).containsExactly("/inApps/v1/transactions/2000000000000001")
        assertThat(sandbox.requests).hasSize(1)
    }

    @Test
    fun `a purchase neither host knows is null`() {
        assertThat(gateway.fetch(claim)).isNull()
        assertThat(prod.requests).hasSize(1)
        assertThat(sandbox.requests).hasSize(1)
    }

    @Test
    fun `amounts become the smallest unit of the currency from thousandths`() {
        prod.knows("2000000000000001", transactionJson(price = 4_990, currency = "USD"))
        assertThat(gateway.fetch(claim)!!.amount).isEqualTo(PaymentAmount(499, "USD"))
        prod.knows("2000000000000002", transactionJson(transactionId = "2000000000000002", price = 1_000_000, currency = "JPY"))
        assertThat(gateway.fetch(StoreClaim("2000000000000002"))!!.amount).isEqualTo(PaymentAmount(1000, "JPY"))
    }

    @Test
    fun `a free trial without a price is zero`() {
        prod.knows("2000000000000001", transactionJson(price = null, currency = null))
        assertThat(gateway.fetch(claim)!!.amount.amount).isZero()
    }

    @Test
    fun `a refunded purchase is revoked`() {
        prod.knows("2000000000000001", transactionJson(revocationDate = NOW.minusSeconds(60), revocationType = "REFUND_FULL"))
        assertThat(gateway.fetch(claim)!!.state).isEqualTo(StorePurchaseState.REVOKED)
    }

    @Test
    fun `the request carries a five minute ES256 token with the issuer, the audience and the bundle id`() {
        prod.knows("2000000000000001", transactionJson())
        gateway.fetch(claim)
        val token = JWSObject.parse(prod.requests.single().auth!!.removePrefix("Bearer "))
        assertThat(token.header.algorithm.name).isEqualTo("ES256")
        assertThat(token.header.keyID).isEqualTo(KEY_ID)
        assertThat(token.header.type.toString()).isEqualTo("JWT")
        assertThat(token.verify(ECDSAVerifier(key.publicKey))).isTrue()
        val claims = com.nimbusds.jwt.JWTClaimsSet.parse(token.payload.toJSONObject())
        assertThat(claims.issuer).isEqualTo(ISSUER)
        assertThat(claims.audience).containsExactly("appstoreconnect-v1")
        assertThat(claims.getStringClaim("bid")).isEqualTo(BUNDLE)
        assertThat(Duration.between(claims.issueTime.toInstant(), claims.expirationTime.toInstant())).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `an answer signed under another root is not believed`() {
        prod.knows("2000000000000001", transactionJson(), signer = TestChain.create())
        assertThatThrownBy { gateway.fetch(claim) }.isInstanceOf(PaymentProviderException::class.java)
    }

    @Test
    fun `an answer for another app is not believed`() {
        prod.knows("2000000000000001", transactionJson(bundleId = "com.example.other"))
        assertThatThrownBy { gateway.fetch(claim) }.isInstanceOf(PaymentProviderException::class.java)
    }

    @Test
    fun `an answer for another transaction is not believed`() {
        prod.knows("2000000000000001", transactionJson(transactionId = "2000000000000999"))
        assertThatThrownBy { gateway.fetch(claim) }.isInstanceOf(PaymentProviderException::class.java)
    }

    @Test
    fun `a rate limit or a server error is thrown so the caller can ask again`() {
        prod.failNext("/inApps/v1/transactions/2000000000000001", 429)
        assertThatThrownBy { gateway.fetch(claim) }.isInstanceOfSatisfying(PaymentProviderException::class.java) { assertThat(it.providerError.retryable).isTrue() }
        prod.failNext("/inApps/v1/transactions/2000000000000001", 500)
        assertThatThrownBy { gateway.fetch(claim) }.isInstanceOf(PaymentProviderException::class.java)
    }

    @Test
    fun `a subscription is read through its status - the latest transaction, the period and the state`() {
        val first = transactionJson(transactionId = "2000000000000010", originalTransactionId = "2000000000000010", productId = "com.example.sample.premium.monthly", type = "Auto-Renewable Subscription", price = 9_900_000, expiresDate = NOW.minusSeconds(3600).plus(Duration.ofDays(30)))
        val latest = transactionJson(transactionId = "2000000000000011", originalTransactionId = "2000000000000010", productId = "com.example.sample.premium.monthly", type = "Auto-Renewable Subscription", price = 9_900_000, purchaseDate = NOW.minusSeconds(60), expiresDate = NOW.plus(Duration.ofDays(30)))
        prod.knows("2000000000000010", first)
        prod.subscription("2000000000000010", status = 1, latest = latest)
        val purchase = gateway.fetch(StoreClaim("2000000000000010"))!!
        assertThat(purchase.kind).isEqualTo(StorePurchaseKind.SUBSCRIPTION)
        assertThat(purchase.providerSubscriptionId).isEqualTo("2000000000000010")
        assertThat(purchase.providerPaymentId).isEqualTo("2000000000000011")
        assertThat(purchase.periodStart).isEqualTo(NOW.minusSeconds(60))
        assertThat(purchase.periodEnd).isEqualTo(NOW.plus(Duration.ofDays(30)))
        assertThat(purchase.subscriptionStatus).isEqualTo(PushedSubscriptionStatus.ACTIVE)
        assertThat(purchase.state).isEqualTo(StorePurchaseState.PURCHASED)
        assertThat(prod.requests.map { it.path }).containsExactly("/inApps/v1/transactions/2000000000000010", "/inApps/v1/subscriptions/2000000000000010")
    }

    @Test
    fun `subscription statuses map to the ledger's states`() {
        val sub = transactionJson(transactionId = "2000000000000010", productId = "p", type = "Auto-Renewable Subscription", expiresDate = NOW.plus(Duration.ofDays(1)))
        prod.knows("2000000000000010", sub)
        fun statusOf(apple: Int, renewal: String = renewalJson()): Pair<PushedSubscriptionStatus?, StorePurchaseState> {
            prod.subscription("2000000000000010", status = apple, latest = sub, renewal = renewal)
            return gateway.fetch(StoreClaim("2000000000000010"))!!.let { it.subscriptionStatus to it.state }
        }
        assertThat(statusOf(1, renewalJson(autoRenewStatus = 0))).isEqualTo(PushedSubscriptionStatus.CANCELING to StorePurchaseState.PURCHASED)
        assertThat(statusOf(2)).isEqualTo(PushedSubscriptionStatus.ENDED to StorePurchaseState.PURCHASED)
        assertThat(statusOf(3)).isEqualTo(PushedSubscriptionStatus.PAST_DUE to StorePurchaseState.PURCHASED)
        assertThat(statusOf(4)).isEqualTo(PushedSubscriptionStatus.ACTIVE to StorePurchaseState.PURCHASED)
        assertThat(statusOf(5)).isEqualTo(PushedSubscriptionStatus.ENDED to StorePurchaseState.REVOKED)
    }

    @Test
    fun `a production subscription answer for another app id is not believed`() {
        val sub = transactionJson(transactionId = "2000000000000010", productId = "p", type = "Auto-Renewable Subscription", expiresDate = NOW.plus(Duration.ofDays(1)))
        prod.knows("2000000000000010", sub)
        prod.subscription("2000000000000010", status = 1, latest = sub, appAppleId = 999)
        assertThatThrownBy { gateway.fetch(StoreClaim("2000000000000010")) }.isInstanceOf(PaymentProviderException::class.java)
    }
}
