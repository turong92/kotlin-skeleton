package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 스펙 §9.3 의 표 전부를 조합표로. 메시지에는 **환경변수 · 속성 이름만** 있고 키 값 · 접두사 뒤의 글자는 없다.
 * 키마다 눈에 띄는 꼬리(`…SUFFIX`)를 붙여 두고 메시지 어디에도 없음을 모든 조합에서 본다.
 */
class TossKeyGuardTest {
    private val unset = DeployContext(null, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())
    private val profileLocal = DeployContext(null, setOf("local"))
    private val profileTest = DeployContext(null, setOf("test"))
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val everyEnv = mapOf("unset" to unset, "local" to local, "profile local" to profileLocal, "profile test" to profileTest, "stage" to stage, "prod" to prod)
    private val localLike = listOf("local", "profile local", "profile test")
    private val protectedEnvs = listOf("stage", "prod")

    private val testSecret = "test_sk_TESTSECRETSUFFIX"
    private val testClient = "test_ck_TESTCLIENTSUFFIX"
    private val liveSecret = "live_sk_LIVESECRETSUFFIX"
    private val liveClient = "live_ck_LIVECLIENTSUFFIX"
    private val suffixes = listOf("TESTSECRETSUFFIX", "TESTCLIENTSUFFIX", "LIVESECRETSUFFIX", "LIVECLIENTSUFFIX", "WIDGETSUFFIX")

    private fun guard(
        secret: String = testSecret,
        client: String = testClient,
        allowTest: Boolean = false,
        ledger: Boolean = false,
        workerRunning: Boolean = true,
        responseTimeout: Duration = Duration.ofSeconds(30),
        firstDelay: Duration = Duration.ofSeconds(60),
        settleDelay: Duration = Duration.ofMinutes(2),
        baseUrl: String = TossPaymentProperties().baseUrl,
    ) = TossKeyGuard(
        properties = TossPaymentProperties(enabled = true, secretKey = secret, clientKey = client, baseUrl = baseUrl, allowTestKeysInProtectedEnv = allowTest),
        ledgerPresent = { ledger },
        jobQueueWorkerRunning = { workerRunning },
        responseTimeout = { responseTimeout },
        reconcileFirstDelay = { firstDelay },
        refundSettleDelay = { settleDelay },
    )

    private fun TossKeyGuard.all(context: DeployContext) = problems(context) + warnings(context)

    private fun assertNoSecrets(label: String, messages: List<String>) {
        messages.forEach { message ->
            suffixes.forEach { assertFalse(message.contains(it), "$label leaked a key tail: $message") }
            assertFalse(message.contains("test_sk") || message.contains("live_sk") || message.contains("test_ck") || message.contains("live_ck"), "$label printed a key prefix: $message")
        }
    }

    @Test
    fun `enabled with no secret key in a protected environment fails the start - a deployment that asked for payments must not come up without them`() {
        protectedEnvs.forEach { env ->
            val g = guard(secret = "", client = "")
            val problems = g.problems(everyEnv.getValue(env))
            assertEquals(1, problems.size, env)
            assertTrue("skeleton.payment-toss.secret-key" in problems.single() && "PAYMENT_TOSS_SECRET_KEY" in problems.single(), problems.single())
            assertEquals(emptyList(), g.warnings(everyEnv.getValue(env)).filter { "secret-key" in it }, "said once, as a problem - not twice")
        }
        (everyEnv - protectedEnvs.toSet()).forEach { (env, context) ->
            assertEquals(emptyList(), guard(secret = "", client = "").problems(context), "$env: only a warning")
        }
    }

    @Test
    fun `client and secret key modes that differ fail the start in every environment`() {
        everyEnv.forEach { (env, context) ->
            listOf(testClient to liveSecret, liveClient to testSecret).forEach { (client, secret) ->
                val g = guard(secret = secret, client = client)
                val problems = g.problems(context)
                val modeProblems = problems.filter { "different modes" in it }
                assertEquals(1, modeProblems.size, "$env $client/$secret")
                assertTrue("skeleton.payment-toss.client-key" in modeProblems.single() && "skeleton.payment-toss.secret-key" in modeProblems.single())
                assertNoSecrets(env, problems)
            }
        }
    }

    @Test
    fun `key kinds that do not belong together fail the start in every environment`() {
        val mismatched = mapOf(
            "widget client with a secret key" to ("test_gck_WIDGETSUFFIX" to testSecret),
            "client key with a widget secret" to (testClient to "test_gsk_WIDGETSUFFIX"),
            "widget pair" to ("test_gck_WIDGETSUFFIX" to "test_gsk_WIDGETSUFFIX"),
            "the secret key in the client slot" to (testSecret to testSecret),
            "the client key in the secret slot" to (testClient to testClient),
            "live widget pair" to ("live_gck_WIDGETSUFFIX" to "live_gsk_WIDGETSUFFIX"),
        )
        everyEnv.forEach { (env, context) ->
            mismatched.forEach { (label, pair) ->
                val g = guard(secret = pair.second, client = pair.first)
                val problems = g.problems(context)
                assertTrue(problems.isNotEmpty(), "$env: $label should fail the start")
                assertNoSecrets("$env $label", problems + g.warnings(context))
            }
        }
    }

    /** 환경에 어긋나지 않는 시크릿 키 — 보호 환경이면 실결제 키, 아니면 테스트 키. */
    private fun fitting(env: String) = if (env in protectedEnvs) liveSecret else testSecret

    @Test
    fun `with a ledger a missing client key fails the start in protected environments and only warns elsewhere`() {
        protectedEnvs.forEach { env ->
            val problems = guard(secret = fitting(env), client = "", ledger = true).problems(everyEnv.getValue(env))
            assertEquals(1, problems.size, env)
            assertTrue("skeleton.payment-toss.client-key" in problems.single() && "PAYMENT_TOSS_CLIENT_KEY" in problems.single())
        }
        listOf("unset", "local", "profile local", "profile test").forEach { env ->
            val g = guard(secret = fitting(env), client = "", ledger = true)
            assertEquals(emptyList(), g.problems(everyEnv.getValue(env)), env)
            assertEquals(1, g.warnings(everyEnv.getValue(env)).size, env)
        }
    }

    @Test
    fun `a live key in a local-like environment fails the start so real money cannot move`() {
        localLike.forEach { env ->
            listOf(liveSecret to liveClient, liveSecret to "", ).forEach { (secret, client) ->
                val problems = guard(secret = secret, client = client).problems(everyEnv.getValue(env))
                assertEquals(1, problems.size, "$env $secret/$client")
                assertTrue("skeleton.payment-toss.secret-key" in problems.single())
                assertNoSecrets(env, problems)
            }
        }
        listOf("unset", "stage", "prod").forEach { env ->
            assertEquals(emptyList(), guard(liveSecret, liveClient).problems(everyEnv.getValue(env)), env)
        }
    }

    @Test
    fun `a test key in a protected environment fails the start unless explicitly allowed - then it is a warning`() {
        protectedEnvs.forEach { env ->
            val context = everyEnv.getValue(env)
            val refused = guard().problems(context)
            assertEquals(1, refused.size, env)
            assertTrue("skeleton.payment-toss.allow-test-keys-in-protected-env" in refused.single(), "the message names the switch to flip")
            assertNoSecrets(env, refused)

            val allowed = guard(allowTest = true)
            assertEquals(emptyList(), allowed.problems(context), env)
            assertEquals(1, allowed.warnings(context).size, env)
            assertNoSecrets(env, allowed.warnings(context))
        }
        localLike.plus("unset").forEach { env ->
            assertEquals(emptyList(), guard().all(everyEnv.getValue(env)), "test keys are the normal case outside protected environments: $env")
        }
    }

    @Test
    fun `the allow switch does not excuse mismatched modes or kinds`() {
        assertTrue(guard(secret = liveSecret, client = testClient, allowTest = true).problems(prod).isNotEmpty())
        assertTrue(guard(secret = testSecret, client = "test_gck_WIDGETSUFFIX", allowTest = true).problems(prod).isNotEmpty())
    }

    @Test
    fun `with a ledger a reconcile first delay that is not longer than the response limit fails the start - the lookup would overlap a confirm still in flight`() {
        val limit = Duration.ofSeconds(60)
        listOf(Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(1)).forEach { delay ->
            everyEnv.forEach { (env, context) ->
                val problems = guard(ledger = true, responseTimeout = limit, firstDelay = delay).problems(context)
                val line = problems.singleOrNull { "skeleton.payment.reconcile.first-delay" in it }
                assertTrue(line != null, "$env $delay: $problems")
                assertTrue("skeleton.http.clients.payment-toss.response-timeout" in line)
            }
        }
    }

    @Test
    fun `with a ledger a refund settle delay that is not longer than the response limit fails the start too`() {
        val problems = guard(ledger = true, responseTimeout = Duration.ofSeconds(30), settleDelay = Duration.ofSeconds(30)).problems(local)
        assertEquals(1, problems.count { "skeleton.payment.refund.settle-delay" in it }, "$problems")
    }

    @Test
    fun `no message in any combination contains a key value or the characters after a key prefix`() {
        val keys = listOf("", testSecret, liveSecret, "test_gsk_WIDGETSUFFIX", "live_gsk_WIDGETSUFFIX", "weird-secret")
        val clients = listOf("", testClient, liveClient, "test_gck_WIDGETSUFFIX", "live_gck_WIDGETSUFFIX", testSecret, "weird-client")
        var checked = 0
        everyEnv.forEach { (env, context) ->
            keys.forEach { secret ->
                clients.forEach { client ->
                    listOf(true, false).forEach { ledger ->
                        listOf(true, false).forEach { allow ->
                            val g = guard(secret, client, allowTest = allow, ledger = ledger, workerRunning = false, responseTimeout = Duration.ofSeconds(1))
                            assertNoSecrets("$env $secret/$client", g.problems(context) + g.warnings(context))
                            assertNoSecrets("$env $secret/$client", listOf(g.name))
                            checked++
                        }
                    }
                }
            }
        }
        assertTrue(checked > 500)
    }
}
