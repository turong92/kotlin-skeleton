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
    fun `enabled with no secret key outside a protected environment - the provider bean is absent - only warns`() {
        (everyEnv - protectedEnvs.toSet()).forEach { (env, context) ->
            val g = guard(secret = "", client = "")
            assertEquals(emptyList(), g.problems(context), env)
            assertEquals(1, g.warnings(context).size, env)
            assertTrue("skeleton.payment-toss.secret-key" in g.warnings(context).single() && "PAYMENT_TOSS_SECRET_KEY" in g.warnings(context).single())
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
    }

    @Test
    fun `a base url that is not the Toss host warns in protected environments only`() {
        listOf("stage", "prod").forEach { env ->
            val custom = guard(baseUrl = "https://payments.example.test").warnings(everyEnv.getValue(env))
            assertEquals(1, custom.count { "base-url" in it }, env)
            assertFalse(custom.joinToString().contains("example.test"), "the host is not printed")
            assertEquals(0, guard().warnings(everyEnv.getValue(env)).count { "base-url" in it }, "the default host is fine")
            assertEquals(0, guard(baseUrl = " https://api.tosspayments.com/ ").warnings(everyEnv.getValue(env)).count { "base-url" in it }, "spacing and a trailing slash do not matter")
            assertEquals(1, guard(baseUrl = "https://api.tosspayments.com.evil.test").warnings(everyEnv.getValue(env)).count { "base-url" in it })
        }
        listOf("unset", "local", "profile local", "profile test").forEach { env ->
            assertEquals(0, guard(baseUrl = "http://127.0.0.1:9999").warnings(everyEnv.getValue(env)).count { "base-url" in it }, "a fake server in a local run is the normal case: $env")
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
    fun `with a ledger in any environment a stopped job queue worker is a warning - lookups, fulfilment and webhooks would not run`() {
        everyEnv.keys.forEach { env ->
            val context = everyEnv.getValue(env)
            val g = guard(fitting(env), if (env in protectedEnvs) liveClient else testClient, ledger = true, workerRunning = false)
            assertEquals(emptyList(), g.problems(context), env)
            val warnings = g.warnings(context)
            assertEquals(1, warnings.size, env)
            assertTrue("skeleton.job-queue.worker-enabled" in warnings.single(), env)
            assertEquals(emptyList(), guard(fitting(env), if (env in protectedEnvs) liveClient else testClient, ledger = true, workerRunning = true).all(context), env)
        }
        assertEquals(emptyList(), guard(liveSecret, liveClient, ledger = false, workerRunning = false).all(prod))
        assertEquals(emptyList(), guard(ledger = false, workerRunning = false).all(local))
    }

    @Test
    fun `with a ledger a response limit shorter than the lookup limit is a warning - an honest confirm would often end up unknown`() {
        val short = guard(ledger = true, responseTimeout = Duration.ofSeconds(5))
        val warnings = short.warnings(local)
        assertEquals(1, warnings.size)
        assertTrue("skeleton.http.clients.payment-toss.response-timeout" in warnings.single())
        assertEquals(emptyList(), short.problems(local))

        assertEquals(emptyList(), guard(ledger = true, responseTimeout = Duration.ofSeconds(10)).all(local))
        assertEquals(emptyList(), guard(ledger = true, responseTimeout = Duration.ofSeconds(30)).all(local))
        assertEquals(emptyList(), guard(ledger = false, responseTimeout = Duration.ofSeconds(5)).all(local), "without a ledger nothing waits on the confirm")
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
    fun `with a ledger a refund settle delay that is not longer than the response limit fails the start - the settle job would judge a refund that is still in flight`() {
        val limit = Duration.ofSeconds(30)
        listOf(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(1)).forEach { delay ->
            everyEnv.forEach { (env, context) ->
                val problems = guard(ledger = true, responseTimeout = limit, settleDelay = delay).problems(context)
                val line = problems.singleOrNull { "skeleton.payment.refund.settle-delay" in it }
                assertTrue(line != null, "$env $delay: $problems")
                assertTrue("skeleton.http.clients.payment-toss.response-timeout" in line)
                assertTrue("${delay.seconds}s" in line && "${limit.seconds}s" in line, "the message names both durations: $line")
                assertTrue(problems.none { "first-delay" in it }, "the first-delay line is its own: $problems")
            }
        }
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

    @Test
    fun `a key that does not look like a Toss key warns without printing it`() {
        val g = guard(secret = "weird-secret", client = "weird-client")
        val warnings = g.warnings(unset)
        assertTrue(warnings.isNotEmpty())
        assertEquals(emptyList(), g.problems(unset))
        warnings.forEach { assertFalse(it.contains("weird")) }
    }
}
