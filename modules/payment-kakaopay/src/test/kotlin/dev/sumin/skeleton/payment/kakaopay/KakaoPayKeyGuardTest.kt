package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 기동 가드: 테스트 가맹점 코드(`TC0ONETIME`)를 보호 환경에서 쓰면 · 실결제 코드를 로컬에서 쓰면 기동 실패. 메시지에는 속성 이름만 있고 키 값은 없다. */
class KakaoPayKeyGuardTest {
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())
    private val profileTest = DeployContext(null, setOf("test"))
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val everyEnv = mapOf("unset" to DeployContext(null, emptySet()), "local" to local, "profile test" to profileTest, "stage" to stage, "prod" to prod)

    private val secret = "SECRETKEYTAIL0123456789"
    private val cidSecret = "CIDSECRETTAIL"

    private fun guard(
        cid: String = "TC0ONETIME",
        secretKey: String = secret,
        returnUrl: String = "https://app.example.test/payments/return",
        allowTest: Boolean = false,
        ledger: Boolean = false,
        workerRunning: Boolean = true,
        responseTimeout: Duration = Duration.ofSeconds(30),
        firstDelay: Duration = Duration.ofSeconds(60),
        settleDelay: Duration = Duration.ofMinutes(2),
        baseUrl: String = KakaoPayProperties().baseUrl,
        origins: Set<String> = emptySet(),
        subscriptionCid: String = "",
    ) = KakaoPayKeyGuard(
        properties = KakaoPayProperties(enabled = true, secretKey = secretKey, cid = cid, subscriptionCid = subscriptionCid, cidSecret = cidSecret, returnUrl = returnUrl, baseUrl = baseUrl, allowTestKeysInProtectedEnv = allowTest, allowedReturnOrigins = origins),
        ledgerPresent = { ledger },
        jobQueueWorkerRunning = { workerRunning },
        responseTimeout = { responseTimeout },
        reconcileFirstDelay = { firstDelay },
        refundSettleDelay = { settleDelay },
    )

    @Test
    fun `a test merchant code in stage or prod stops the start, unless the owner allows a test deployment on purpose - then it is a warning`() {
        for (env in listOf(stage, prod)) {
            assertTrue(guard().problems(env).any { "test merchant code" in it }, guard().problems(env).toString())
            assertEquals(emptyList(), guard(allowTest = true).problems(env))
            assertTrue(guard(allowTest = true).warnings(env).any { "test merchant code" in it })
        }
        assertEquals(emptyList(), guard().problems(local), "a test code is what local wants")
    }

    @Test
    fun `a live merchant code on a local or test environment stops the start - real money must not move from there`() {
        for (env in listOf(local, profileTest)) {
            assertTrue(guard(cid = "1234567890").problems(env).any { "live merchant code" in it }, "$env")
        }
        assertEquals(emptyList(), guard(cid = "1234567890").problems(prod))
        assertEquals(emptyList(), guard(cid = "1234567890").problems(DeployContext(null, emptySet())))
    }

    @Test
    fun `in a protected environment a missing secret key or merchant code stops the start, elsewhere it is a warning`() {
        for (env in listOf(stage, prod)) {
            assertTrue(guard(secretKey = "", cid = "1234567890").problems(env).any { "secret-key" in it })
            assertTrue(guard(cid = "", allowTest = true).problems(env).any { "payment-kakaopay.cid" in it })
        }
        assertEquals(emptyList(), guard(secretKey = "").problems(local))
        assertTrue(guard(secretKey = "").warnings(local).any { "secret-key" in it })
        assertTrue(guard(cid = "").warnings(local).any { "payment-kakaopay.cid" in it })
    }

    @Test
    fun `with the ledger present, a return address that is not an http address stops a protected start, and the delays must outlast the response limit`() {
        assertTrue(guard(ledger = true, returnUrl = "", cid = "1234567890").problems(prod).any { "return-url" in it })
        assertTrue(guard(ledger = true, returnUrl = "/relative", cid = "1234567890").problems(prod).any { "return-url" in it })
        assertEquals(emptyList(), guard(ledger = false, returnUrl = "", cid = "1234567890").problems(prod))
        assertTrue(guard(ledger = true, firstDelay = Duration.ofSeconds(30)).problems(local).any { "first-delay" in it })
        assertTrue(guard(ledger = true, settleDelay = Duration.ofSeconds(30)).problems(local).any { "settle-delay" in it })
        assertEquals(emptyList(), guard(ledger = true).problems(local))
    }

    @Test
    fun `warnings - an address that is not KakaoPay's in a protected environment, an http return address, a stopped worker, a short response limit`() {
        assertTrue(guard(cid = "1234567890", baseUrl = "https://proxy.example.test").warnings(prod).any { "base-url" in it })
        assertEquals(emptyList(), guard(cid = "1234567890", baseUrl = " https://open-api.kakaopay.com/ ").warnings(prod))
        assertTrue(guard(cid = "1234567890", returnUrl = "http://app.example.test/r").warnings(prod).any { "https" in it })
        assertTrue(guard(ledger = true, workerRunning = false).warnings(local).any { "job queue worker" in it })
        assertTrue(guard(ledger = true, responseTimeout = Duration.ofSeconds(5)).warnings(local).any { "response-timeout" in it })
    }

    @Test
    fun `no message in any combination carries the secret key, the merchant code authentication key or the base address`() {
        val combos = listOf(
            guard(),
            guard(secretKey = "", cid = ""),
            guard(cid = "1234567890", ledger = true, returnUrl = "", baseUrl = "https://leak.example.test/SECRETPATH"),
            guard(ledger = true, firstDelay = Duration.ofSeconds(1), settleDelay = Duration.ofSeconds(1), allowTest = true),
        )
        for ((label, env) in everyEnv) {
            for (g in combos) {
                (g.problems(env) + g.warnings(env)).forEach { message ->
                    assertFalse(secret in message || cidSecret in message || "SECRETPATH" in message || "leak.example.test" in message, "$label leaked: $message")
                }
            }
        }
    }

    @Test
    fun `an allowed return origin that is not a bare origin or is not https in a protected environment stops the start - elsewhere it only warns`() {
        val live = "1234567890"
        assertTrue(guard(cid = live, origins = setOf("https://app.example.test/path")).problems(prod).any { "allowed-return-origins" in it })
        assertTrue(guard(cid = live, origins = setOf("http://app.example.test")).problems(prod).any { "non-https" in it })
        assertEquals(emptyList(), guard(cid = live, origins = setOf("https://app.example.test", "https://dev.example.test/")).problems(prod))
        assertEquals(emptyList(), guard(origins = setOf("http://localhost:5173")).problems(local))
        assertTrue(guard(origins = setOf("localhost:5173")).warnings(local).any { "allowed-return-origins" in it })
    }

    @Test
    fun `a subscription merchant code of the other mode stops the start - it would register in one world and charge in the other`() {
        val mixed = guard(cid = "TC0ONETIME", subscriptionCid = "A010101010")
        assertTrue(mixed.problems(local).any { "same mode" in it }, mixed.problems(local).toString())
        assertEquals(emptyList(), guard(cid = "TC0ONETIME", subscriptionCid = "TCSUBSCRIP").problems(local))
        assertFalse(mixed.problems(local).any { "A010101010" in it })
    }
}
