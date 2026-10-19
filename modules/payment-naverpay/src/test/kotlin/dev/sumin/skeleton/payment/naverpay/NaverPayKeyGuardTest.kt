package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 기동 가드: 개발 모드를 보호 환경에서 쓰면 · 운영 모드를 로컬에서 쓰면 기동 실패. 메시지에는 속성 이름만 있고 키 값은 없다. */
class NaverPayKeyGuardTest {
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())
    private val profileTest = DeployContext(null, setOf("test"))
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val everyEnv = mapOf("unset" to DeployContext(null, emptySet()), "local" to local, "profile test" to profileTest, "stage" to stage, "prod" to prod)

    private val secret = "CLIENTSECRETTAIL0123456789"
    private val clientId = "CLIENTIDTAIL0123"
    private val chainId = "CHAINIDTAIL01"

    private fun guard(
        mode: NaverPayMode = NaverPayMode.DEVELOPMENT,
        clientSecret: String = secret,
        id: String = clientId,
        chain: String = chainId,
        returnUrl: String = "https://app.example.test/payments/return",
        allowDevelopment: Boolean = false,
        ledger: Boolean = false,
        workerRunning: Boolean = true,
        responseTimeout: Duration = Duration.ofSeconds(60),
        firstDelay: Duration = Duration.ofSeconds(90),
        settleDelay: Duration = Duration.ofMinutes(3),
        baseUrl: String = "",
        windowBaseUrl: String = "",
        origins: Set<String> = emptySet(),
    ) = NaverPayKeyGuard(
        properties = NaverPayProperties(enabled = true, mode = mode, clientId = id, clientSecret = clientSecret, chainId = chain, returnUrl = returnUrl, baseUrl = baseUrl, windowBaseUrl = windowBaseUrl, allowDevelopmentModeInProtectedEnv = allowDevelopment, allowedReturnOrigins = origins),
        ledgerPresent = { ledger },
        jobQueueWorkerRunning = { workerRunning },
        responseTimeout = { responseTimeout },
        reconcileFirstDelay = { firstDelay },
        refundSettleDelay = { settleDelay },
    )

    @Test
    fun `development mode in stage or prod stops the start, unless the owner allows a test deployment on purpose - then it is a warning`() {
        for (env in listOf(stage, prod)) {
            assertTrue(guard().problems(env).any { "development mode" in it }, guard().problems(env).toString())
            assertEquals(emptyList(), guard(allowDevelopment = true).problems(env))
            assertTrue(guard(allowDevelopment = true).warnings(env).any { "development mode" in it })
            assertEquals(emptyList(), guard(mode = NaverPayMode.PRODUCTION).problems(env))
        }
        assertEquals(emptyList(), guard().problems(local), "development mode is what local wants")
    }

    @Test
    fun `production mode on a local or test environment stops the start - real money must not move from there`() {
        for (env in listOf(local, profileTest)) {
            assertTrue(guard(mode = NaverPayMode.PRODUCTION).problems(env).any { "production mode" in it }, "$env")
        }
        assertEquals(emptyList(), guard(mode = NaverPayMode.PRODUCTION).problems(DeployContext(null, emptySet())))
    }

    @Test
    fun `in a protected environment a missing client id, secret or chain id stops the start, elsewhere it is a warning`() {
        for (env in listOf(stage, prod)) {
            assertTrue(guard(clientSecret = "", mode = NaverPayMode.PRODUCTION).problems(env).any { "client-secret" in it })
            assertTrue(guard(id = "", mode = NaverPayMode.PRODUCTION).problems(env).any { "client-id" in it })
            assertTrue(guard(chain = "", mode = NaverPayMode.PRODUCTION).problems(env).any { "chain-id" in it })
        }
        assertEquals(emptyList(), guard(clientSecret = "").problems(local))
        assertTrue(guard(clientSecret = "").warnings(local).any { "client-secret" in it })
        assertTrue(guard(id = "").warnings(local).any { "client-id" in it })
        assertTrue(guard(chain = "").warnings(local).any { "chain-id" in it })
    }

    @Test
    fun `with the ledger present, a return address that is not an http address stops a protected start, and the delays must outlast the response limit`() {
        assertTrue(guard(ledger = true, returnUrl = "", mode = NaverPayMode.PRODUCTION).problems(prod).any { "return-url" in it })
        assertTrue(guard(ledger = true, returnUrl = "/relative", mode = NaverPayMode.PRODUCTION).problems(prod).any { "return-url" in it })
        assertEquals(emptyList(), guard(ledger = false, returnUrl = "", mode = NaverPayMode.PRODUCTION).problems(prod))
        assertTrue(guard(ledger = true, firstDelay = Duration.ofSeconds(60)).problems(local).any { "first-delay" in it })
        assertTrue(guard(ledger = true, settleDelay = Duration.ofSeconds(60)).problems(local).any { "settle-delay" in it })
        assertEquals(emptyList(), guard(ledger = true).problems(local))
    }

    @Test
    fun `warnings - an address that is not NaverPay's in a protected environment, an http return address, a stopped worker, a response limit under the documented 60 seconds`() {
        assertTrue(guard(mode = NaverPayMode.PRODUCTION, baseUrl = "https://proxy.example.test").warnings(prod).any { "base-url" in it })
        assertEquals(emptyList(), guard(mode = NaverPayMode.PRODUCTION, baseUrl = " https://pay.paygate.naver.com/ ").warnings(prod))
        assertTrue(guard(mode = NaverPayMode.PRODUCTION, returnUrl = "http://app.example.test/r").warnings(prod).any { "https" in it })
        assertTrue(guard(ledger = true, workerRunning = false).warnings(local).any { "job queue worker" in it })
        assertTrue(guard(ledger = true, responseTimeout = Duration.ofSeconds(30)).warnings(local).any { "response-timeout" in it })
    }

    @Test
    fun `an address of the other mode's NaverPay host stops the start - the api address everywhere, the window address in a protected environment`() {
        // 개발 모드인데 운영 호스트 / 운영 모드인데 개발 호스트
        assertTrue(guard(baseUrl = "https://pay.paygate.naver.com").problems(local).any { "base-url" in it && "other mode" in it })
        assertTrue(guard(mode = NaverPayMode.PRODUCTION, baseUrl = "https://dev-pay.paygate.naver.com/").problems(prod).any { "base-url" in it })
        assertTrue(guard(mode = NaverPayMode.PRODUCTION, windowBaseUrl = "https://test-m.pay.naver.com").problems(prod).any { "window-base-url" in it })
        assertTrue(guard(allowDevelopment = true, windowBaseUrl = "https://m.pay.naver.com").problems(stage).any { "window-base-url" in it })
        // 같은 모드의 호스트 · 다른 주소(프록시 · 가짜 서버)는 말하지 않는다
        assertEquals(emptyList(), guard(baseUrl = "https://dev-pay.paygate.naver.com", windowBaseUrl = "https://test-m.pay.naver.com").problems(local))
        assertEquals(emptyList(), guard(baseUrl = "http://127.0.0.1:9", windowBaseUrl = "http://127.0.0.1:9").problems(local))
    }

    @Test
    fun `no message in any combination carries the client secret, the ids or the base address`() {
        val combos = listOf(
            guard(),
            guard(clientSecret = "", id = "", chain = ""),
            guard(mode = NaverPayMode.PRODUCTION, ledger = true, returnUrl = "", baseUrl = "https://leak.example.test/SECRETPATH"),
            guard(ledger = true, firstDelay = Duration.ofSeconds(1), settleDelay = Duration.ofSeconds(1), allowDevelopment = true),
        )
        for ((label, env) in everyEnv) {
            for (g in combos) {
                (g.problems(env) + g.warnings(env)).forEach { message ->
                    assertFalse(secret in message || clientId in message || chainId in message || "SECRETPATH" in message || "leak.example.test" in message, "$label leaked: $message")
                }
            }
        }
    }

    @Test
    fun `an allowed return origin that is not a bare origin or is not https in a protected environment stops the start - elsewhere it only warns`() {
        val live = NaverPayMode.PRODUCTION
        assertTrue(guard(mode = live, origins = setOf("https://app.example.test/path")).problems(prod).any { "allowed-return-origins" in it })
        assertTrue(guard(mode = live, origins = setOf("http://app.example.test")).problems(prod).any { "non-https" in it })
        assertEquals(emptyList(), guard(mode = live, origins = setOf("https://app.example.test", "https://dev.example.test/")).problems(prod))
        assertEquals(emptyList(), guard(origins = setOf("http://localhost:5173")).problems(local))
        assertTrue(guard(origins = setOf("localhost:5173")).warnings(local).any { "allowed-return-origins" in it })
    }
}
