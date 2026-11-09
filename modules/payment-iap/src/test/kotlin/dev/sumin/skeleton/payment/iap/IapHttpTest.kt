package dev.sumin.skeleton.payment.iap

import dev.sumin.skeleton.iaptest.IapLedgerBeans
import dev.sumin.skeleton.iaptest.IapWebTestApplication
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PushedSubscriptionStatus
import dev.sumin.skeleton.payment.StoreAccountTokens
import dev.sumin.skeleton.payment.StorePurchase
import dev.sumin.skeleton.payment.StorePurchaseKind
import dev.sumin.skeleton.payment.StorePurchaseState
import dev.sumin.skeleton.payment.testing.FakeStoreGateway
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.RecordingOrderListener
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 앱용 HTTP 둘(설정 · 구매 확인) - 진짜 자동설정 순서로 조립한 메모리 장부와 가짜 스토어 위에서 돈다. 돈 · 보안 경로: 다른 계정의 구매는 409 이고 줄이 안 생긴다,
 * 환불된 구매 · 지도에 없는 상품 · 가족 공유는 422 이고 지급이 없다, 스토어의 일시 실패는 503 (앱이 끝내지 않고 다시 보낸다) 이다.
 */
@SpringBootTest(
    classes = [IapWebTestApplication::class],
    properties = [
        "skeleton.payment-iap.enabled=true",
        "skeleton.payment-iap.web-checkout-url=https://example.com/pricing",
        "skeleton.payment-iap.channels.countries.US.ios=IAP_AND_WEB",
        "skeleton.payment-iap.products[0].item-type=cheer",
        "skeleton.payment-iap.products[0].item-ref=cheer-3000",
        "skeleton.payment-iap.products[0].ios-product-id=com.example.cheer3000",
        "skeleton.payment-iap.products[0].play-product-id=cheer_3000",
        "skeleton.payment-iap.products[1].plan=basic",
        "skeleton.payment-iap.products[1].ios-product-id=com.example.basic.monthly",
        "skeleton.payment-iap.products[1].play-product-id=basic",
        "skeleton.payment-iap.products[1].play-base-plan-id=monthly",
        "skeleton.payment-iap.products[2].item-type=cheer",
        "skeleton.payment-iap.products[2].item-ref=cheer-retired",
        "skeleton.payment-iap.products[2].ios-product-id=com.example.cheer.retired",
        "skeleton.payment-iap.products[3].item-type=ads-removal",
        "skeleton.payment-iap.products[3].item-ref=v1",
        "skeleton.payment-iap.products[3].consumable=false",
        "skeleton.payment-iap.products[3].ios-product-id=com.example.adsfree",
        "skeleton.payment-iap.products[3].play-product-id=ads_free",
        "skeleton.payment.subscription.enabled=true",
        "skeleton.payment.http.order-rate-limit.account-capacity=100000",
        "skeleton.payment.http.order-rate-limit.ip-capacity=100000",
    ],
)
@AutoConfigureMockMvc
@Import(IapLedgerBeans::class)
class IapHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var ledger: InMemoryPaymentLedgerStore
    @Autowired @Qualifier("appstore") lateinit var appstore: FakeStoreGateway
    @Autowired @Qualifier("googleplay") lateinit var googleplay: FakeStoreGateway
    @Autowired lateinit var listener: RecordingOrderListener

    private val base = "/api/v1/payments/iap"
    private val now: Instant = Instant.now()
    private var seq = 0

    private fun account() = "acc_iap_${System.nanoTime()}_${seq++}"

    private fun MockHttpServletRequestBuilder.asUser(accountId: String) = principal(UsernamePasswordAuthenticationToken(accountId, "n/a", emptyList()))

    private fun txn() = "20000${System.nanoTime()}${seq++}"

    private fun cheer(txn: String, token: String?, product: String = "com.example.cheer3000", state: StorePurchaseState = StorePurchaseState.PURCHASED, quantity: Int = 1, familyShared: Boolean = false) = StorePurchase(
        mode = PaymentMode.LIVE, kind = StorePurchaseKind.ONE_TIME, productId = product, providerPaymentId = txn, accountToken = token,
        amount = PaymentAmount(3_000, "KRW"), quantity = quantity, paidAt = now, state = state, familyShared = familyShared,
    )

    private fun adsFree(txn: String, token: String?, product: String = "com.example.adsfree") = StorePurchase(
        mode = PaymentMode.LIVE, kind = StorePurchaseKind.ONE_TIME, productId = product, providerPaymentId = txn, accountToken = token,
        amount = PaymentAmount(3_300, "KRW"), quantity = 1, paidAt = now, state = StorePurchaseState.PURCHASED,
    )

    private fun entitlements(account: String?) = mvc.perform(get("$base/entitlements").let { if (account == null) it else it.asUser(account) })

    private fun basic(txn: String, original: String, token: String?) = StorePurchase(
        mode = PaymentMode.LIVE, kind = StorePurchaseKind.SUBSCRIPTION, productId = "com.example.basic.monthly", providerPaymentId = txn, providerSubscriptionId = original, accountToken = token,
        amount = PaymentAmount(3_900, "KRW"), paidAt = now, periodStart = now, periodEnd = now.plusSeconds(30 * 86_400), state = StorePurchaseState.PURCHASED, subscriptionStatus = PushedSubscriptionStatus.ACTIVE,
    )

    private fun knows(purchase: StorePurchase, store: FakeStoreGateway = appstore): String = purchase.providerPaymentId.also { store.known[it] = purchase }

    private fun confirm(account: String, txn: String, store: String = "appstore"): ResultActions =
        mvc.perform(post("$base/$store/purchases").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"transactionId":"$txn"}"""))

    private fun orders(account: String) = ledger.listOrdersForOperator(dev.sumin.skeleton.payment.OperatorOrderScope.ALL, dev.sumin.skeleton.payment.OperatorOrderFilter(), null, null, 1000).filter { it.accountId == account }

    // --- GET /config

    @Test
    fun `the config tells the app the channel of its platform and country, its account token and the products it can sell`() {
        val account = account()

        mvc.perform(get("$base/config").param("platform", "ios").param("storefront", "USA").asUser(account))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.channel").value("IAP_AND_WEB"))
            .andExpect(jsonPath("$.value.webCheckoutUrl").value("https://example.com/pricing"))
            .andExpect(jsonPath("$.value.store").value("appstore"))
            .andExpect(jsonPath("$.value.appAccountToken").value(UUID.nameUUIDFromBytes("payment-iap:$account".toByteArray()).toString()))
            .andExpect(jsonPath("$.value.appAccountToken").value(StoreAccountTokens.of(account)))
            .andExpect(jsonPath("$.value.products[0].kind").value("ONE_TIME"))
            .andExpect(jsonPath("$.value.products[0].itemRef").value("cheer-3000"))
            .andExpect(jsonPath("$.value.products[0].storeProductId").value("com.example.cheer3000"))
            .andExpect(jsonPath("$.value.products[1].kind").value("SUBSCRIPTION"))
            .andExpect(jsonPath("$.value.products[1].plan").value("basic"))
            .andExpect(jsonPath("$.value.products[1].storeProductId").value("com.example.basic.monthly"))
    }

    @Test
    fun `the config carries consumable on one-time lines - true unless the map says false - and not on subscriptions`() {
        mvc.perform(get("$base/config").param("platform", "ios").asUser(account()))
            .andExpect(jsonPath("$.value.products[0].consumable").value(true))
            .andExpect(jsonPath("$.value.products[1].consumable").doesNotExist())
            .andExpect(jsonPath("$.value.products[3].itemType").value("ads-removal"))
            .andExpect(jsonPath("$.value.products[3].consumable").value(false))
    }

    // --- GET /entitlements (돈 · 권리: 장부의 PAID 단건만, 계정마다)

    @Test
    fun `a paid non-consumable purchase shows in the entitlements of the account that bought it - and of nobody else`() {
        val owner = account()
        val other = account()
        entitlements(owner).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(0))

        confirm(owner, knows(adsFree(txn(), StoreAccountTokens.of(owner)))).andExpect(status().isOk)

        entitlements(owner).andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(1))
            .andExpect(jsonPath("$.values[0].itemType").value("ads-removal"))
            .andExpect(jsonPath("$.values[0].itemRef").value("v1"))
        entitlements(other).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(0))
    }

    @Test
    fun `another account's order never leaks in even when that account holds the entitlement - and the same item bought at the other store counts`() {
        val owner = account()
        val other = account()
        confirm(owner, knows(adsFree(txn(), StoreAccountTokens.of(owner)))).andExpect(status().isOk)
        val token = knows(adsFree(txn(), StoreAccountTokens.of(other), product = "ads_free"), googleplay)
        mvc.perform(post("$base/googleplay/purchases").asUser(other).contentType(MediaType.APPLICATION_JSON).content("""{"productId":"ads_free","purchaseToken":"$token"}""")).andExpect(status().isOk)

        entitlements(other).andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].itemRef").value("v1"))
        assertEquals(1, orders(other).size)
        assertEquals(1, orders(owner).size)
    }

    @Test
    fun `a refunded or partially refunded purchase gives no entitlement`() {
        listOf(PaymentOrderStatus.REFUNDED, PaymentOrderStatus.PARTIALLY_REFUNDED).forEach { refunded ->
            val account = account()
            confirm(account, knows(adsFree(txn(), StoreAccountTokens.of(account)))).andExpect(status().isOk)
            entitlements(account).andExpect(jsonPath("$.values.length()").value(1))

            val order = orders(account).single()
            ledger.inTransaction { tx -> tx.lockOrder(order.id); tx.updateOrder(order.copy(status = refunded)) }

            entitlements(account).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(0))
        }
    }

    @Test
    fun `a consumable line never appears in the entitlements however often it was bought`() {
        val account = account()
        confirm(account, knows(cheer(txn(), StoreAccountTokens.of(account)))).andExpect(status().isOk)
        confirm(account, knows(cheer(txn(), StoreAccountTokens.of(account)))).andExpect(status().isOk)

        assertEquals(2, orders(account).size)
        entitlements(account).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(0))
    }

    @Test
    fun `the entitlements need a login`() {
        entitlements(null).andExpect(status().isUnauthorized)
    }

    @Test
    fun `other countries and the other platform stay in-app only with no link, and the android list carries the base plan`() {
        val account = account()

        mvc.perform(get("$base/config").param("platform", "ios").param("storefront", "KOR").asUser(account))
            .andExpect(jsonPath("$.value.channel").value("IAP_ONLY")).andExpect(jsonPath("$.value.webCheckoutUrl").doesNotExist())
        mvc.perform(get("$base/config").param("platform", "ios").asUser(account))
            .andExpect(jsonPath("$.value.channel").value("IAP_ONLY")).andExpect(jsonPath("$.value.webCheckoutUrl").doesNotExist())
        mvc.perform(get("$base/config").param("platform", "android").param("storefront", "US").asUser(account))
            .andExpect(jsonPath("$.value.channel").value("IAP_ONLY"))
            .andExpect(jsonPath("$.value.store").value("googleplay"))
            .andExpect(jsonPath("$.value.products[1].storeProductId").value("basic"))
            .andExpect(jsonPath("$.value.products[1].basePlanId").value("monthly"))
    }

    @Test
    fun `without a web handoff issuer there is no web checkout link route at all`() {
        mvc.perform(post("$base/web-checkout-link").param("platform", "ios").param("storefront", "USA").asUser(account())).andExpect(status().isNotFound)
    }

    @Test
    fun `the config needs a known platform and a login`() {
        mvc.perform(get("$base/config").asUser(account())).andExpect(status().isBadRequest)
        mvc.perform(get("$base/config").param("platform", "windows").asUser(account())).andExpect(status().isBadRequest)
        mvc.perform(get("$base/config").param("platform", "ios")).andExpect(status().isUnauthorized)
    }

    // --- POST /{store}/purchases

    @Test
    fun `a purchase the store confirms is granted once - confirming it again answers the same order and grants nothing more`() {
        val account = account()
        val txn = knows(cheer(txn(), StoreAccountTokens.of(account)))
        val paidBefore = listener.paid.size

        val first = confirm(account, txn).andExpect(status().isOk).andExpect(jsonPath("$.value.result").value("GRANTED")).andExpect(jsonPath("$.value.kind").value("ONE_TIME")).andReturn().response.contentAsString
        confirm(account, txn).andExpect(status().isOk).andExpect(jsonPath("$.value.result").value("GRANTED"))

        val order = orders(account).single()
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertTrue(first.contains(order.id))
        assertEquals(paidBefore + 1, listener.paid.size)
        assertTrue(appstore.acknowledged.any { it.providerPaymentId == txn })
    }

    @Test
    fun `a purchase that carries another account's token is a 409 and leaves no order behind`() {
        val owner = account()
        val intruder = account()
        val txn = knows(cheer(txn(), StoreAccountTokens.of(owner)))
        val paidBefore = listener.paid.size

        confirm(intruder, txn).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PURCHASE_OWNED_ELSEWHERE"))

        assertTrue(orders(intruder).isEmpty())
        assertTrue(orders(owner).isEmpty())
        assertEquals(paidBefore, listener.paid.size)
        confirm(owner, txn).andExpect(status().isOk)
    }

    @Test
    fun `a purchase without a token belongs to the account that confirmed it first`() {
        val first = account()
        val second = account()
        val txn = knows(cheer(txn(), null))

        confirm(first, txn).andExpect(status().isOk)
        confirm(second, txn).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PURCHASE_OWNED_ELSEWHERE"))

        assertTrue(orders(second).isEmpty())
        assertEquals(1, orders(first).size)
    }

    @Test
    fun `a purchase the store already refunded is a 422 and grants nothing`() {
        val account = account()
        val txn = knows(cheer(txn(), StoreAccountTokens.of(account), state = StorePurchaseState.REVOKED))
        val paidBefore = listener.paid.size

        confirm(account, txn).andExpect(status().`is`(422)).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PURCHASE_REVOKED"))

        assertTrue(orders(account).isEmpty())
        assertEquals(paidBefore, listener.paid.size)
    }

    @Test
    fun `a product that is not in the map is a 422 and grants nothing`() {
        val account = account()
        val txn = knows(cheer(txn(), StoreAccountTokens.of(account), product = "com.example.somethingelse"))

        confirm(account, txn).andExpect(status().`is`(422)).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PRODUCT_UNKNOWN"))

        assertTrue(orders(account).isEmpty())
    }

    @Test
    fun `a product of the other kind than the map says is not granted either`() {
        val account = account()
        val txn = knows(basic(txn(), original = txn(), token = StoreAccountTokens.of(account)).copy(productId = "com.example.cheer3000"))

        confirm(account, txn).andExpect(status().`is`(422)).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PRODUCT_UNKNOWN"))
    }

    @Test
    fun `a product the shop no longer sells is still granted - the buyer already paid`() {
        val account = account()
        val txn = knows(cheer(txn(), StoreAccountTokens.of(account), product = "com.example.cheer.retired"))

        confirm(account, txn).andExpect(status().isOk)

        assertEquals("cheer-retired", orders(account).single().itemRef)
    }

    @Test
    fun `a family shared purchase or a quantity of two or more is a 422 and grants nothing`() {
        val account = account()
        val shared = knows(cheer(txn(), StoreAccountTokens.of(account), familyShared = true))
        val many = knows(cheer(txn(), StoreAccountTokens.of(account), quantity = 2))

        confirm(account, shared).andExpect(status().`is`(422)).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PURCHASE_NOT_ELIGIBLE"))
        confirm(account, many).andExpect(status().`is`(422)).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PURCHASE_NOT_ELIGIBLE"))

        assertTrue(orders(account).isEmpty())
    }

    @Test
    fun `a store that cannot be reached is a 503 and writes nothing - the app is not told to finish, and the same request works once the store is back`() {
        val account = account()
        val txn = knows(cheer(txn(), StoreAccountTokens.of(account)))
        appstore.fetchFailure = ProviderDown()

        try {
            confirm(account, txn).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))
            assertTrue(orders(account).isEmpty())
        } finally {
            appstore.fetchFailure = null
        }

        confirm(account, txn).andExpect(status().isOk)
        assertEquals(1, orders(account).size)
    }

    @Test
    fun `a purchase the store does not know is a 404 - an unknown store is a 404 too`() {
        val account = account()

        confirm(account, "999000999").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PURCHASE_NOT_FOUND"))
        confirm(account, "999000999", store = "windowsstore").andExpect(status().isNotFound)
    }

    @Test
    fun `a purchase the store has not completed is a 202 - nothing is written`() {
        val account = account()
        val txn = knows(cheer(txn(), StoreAccountTokens.of(account), state = StorePurchaseState.PENDING))

        confirm(account, txn).andExpect(status().isAccepted).andExpect(jsonPath("$.value.result").value("PENDING"))

        assertTrue(orders(account).isEmpty())
    }

    @Test
    fun `a first subscription is granted with its line - the same store subscription for another account is a 409`() {
        val account = account()
        val other = account()
        val original = txn()
        val txn = knows(basic(txn(), original, StoreAccountTokens.of(account)))

        confirm(account, txn).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.kind").value("SUBSCRIPTION"))
            .andExpect(jsonPath("$.value.subscription.status").value("ACTIVE"))
            .andExpect(jsonPath("$.value.subscription.planCode").value("basic"))
        confirm(other, txn).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PURCHASE_OWNED_ELSEWHERE"))
    }

    @Test
    fun `a second subscription for an account that already has one is a 409 already subscribed - the money stays in the ledger`() {
        val account = account()
        val firstTxn = knows(basic(txn(), txn(), StoreAccountTokens.of(account)))
        val secondTxn = knows(basic(txn(), txn(), StoreAccountTokens.of(account)))

        confirm(account, firstTxn).andExpect(status().isOk)
        confirm(account, secondTxn).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.ALREADY_SUBSCRIBED"))

        assertEquals(2, orders(account).size)
    }

    @Test
    fun `a google purchase whose product hint differs from what the store says is not believed`() {
        val account = account()
        val token = "token-${System.nanoTime()}"
        googleplay.known[token] = cheer(token, StoreAccountTokens.of(account), product = "cheer_3000")

        mvc.perform(post("$base/googleplay/purchases").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"productId":"cheer_5000","purchaseToken":"$token"}"""))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.IAP_PURCHASE_NOT_FOUND"))
        assertTrue(orders(account).isEmpty())

        mvc.perform(post("$base/googleplay/purchases").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"productId":"cheer_3000","purchaseToken":"$token"}"""))
            .andExpect(status().isOk)
        assertEquals("googleplay", orders(account).single().provider)
    }

    @Test
    fun `a body without its store reference is a 400 and without a login a 401`() {
        val account = account()

        mvc.perform(post("$base/appstore/purchases").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{}""")).andExpect(status().isBadRequest)
        mvc.perform(post("$base/googleplay/purchases").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"purchaseToken":"t"}""")).andExpect(status().isBadRequest)
        mvc.perform(post("$base/appstore/purchases").contentType(MediaType.APPLICATION_JSON).content("""{"transactionId":"1"}""")).andExpect(status().isUnauthorized)
    }

    private class ProviderDown : dev.sumin.skeleton.common.http.ExternalHttpException(
        message = "store down", clientName = "fake-store", method = "GET", uri = URI.create("http://store.invalid/x"), upstreamStatus = 503, retryable = true,
        errorCode = dev.sumin.skeleton.payment.PaymentErrorCode.PROVIDER_ERROR,
    )
}
