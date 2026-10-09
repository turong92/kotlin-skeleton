package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 스펙 §9.3 의 한 줄 — 서명 없는 결제사의 웹훅이 있는데 `webhook.path-token` 이 비었거나 약하면 보호 환경(stage · prod)에서 기동 실패, 그 밖에서는 경고.
 * 서명 없는 결제사가 없거나 웹훅을 껐으면 아무 말도 하지 않는다. 메시지에는 속성 · 환경변수 이름만 있고 비밀 조각의 값은 없다.
 */
class PaymentWebhookDeployGuardTest {
    private val unset = DeployContext(null, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val everyEnv = mapOf("unset" to unset, "local" to local, "stage" to stage, "prod" to prod)
    private val protectedEnvs = listOf("stage", "prod")

    private val strong = "Strong-Webhook.Token_0123456789~abcd"
    private val tooShort = "SECRETSUFFIXshort" // 17자
    private val unsafeChars = "SECRETSUFFIX-with-a-slash/and-spaces ok-ok"
    private val monotone = "a".repeat(40)
    private val weakOnes = mapOf("empty" to "", "blank" to "   ", "too short" to tooShort, "unsafe characters" to unsafeChars, "monotone" to monotone)

    private fun guard(token: String, unsigned: List<String> = listOf("toss"), enabled: Boolean = true) =
        PaymentWebhookDeployGuard(PaymentProperties(webhook = PaymentProperties.Webhook(enabled = enabled, pathToken = token)), unsignedProviders = { unsigned })

    @Test
    fun `an empty or weak path token stops a protected environment and only warns elsewhere`() {
        weakOnes.forEach { (kind, token) ->
            everyEnv.forEach { (envName, context) ->
                val guard = guard(token)
                if (envName in protectedEnvs) {
                    assertEquals(1, guard.problems(context).size, "$kind in $envName is a startup failure")
                    assertTrue(guard.warnings(context).isEmpty(), "$kind in $envName: not said twice")
                } else {
                    assertTrue(guard.problems(context).isEmpty(), "$kind in $envName must not stop startup")
                    assertEquals(1, guard.warnings(context).size, "$kind in $envName warns")
                }
            }
        }
    }

    @Test
    fun `a strong token says nothing anywhere`() {
        everyEnv.forEach { (envName, context) ->
            assertTrue(guard(strong).problems(context).isEmpty(), envName)
            assertTrue(guard(strong).warnings(context).isEmpty(), envName)
        }
    }

    @Test
    fun `without a provider that sends unsigned webhooks - or with the webhook route off - the guard has nothing to say`() {
        weakOnes.values.forEach { token ->
            everyEnv.values.forEach { context ->
                listOf(guard(token, unsigned = emptyList()), guard(token, enabled = false)).forEach {
                    assertTrue(it.problems(context).isEmpty())
                    assertTrue(it.warnings(context).isEmpty())
                }
            }
        }
    }

    @Test
    fun `the messages name the property and the environment variable and never the token`() {
        val messages = weakOnes.values.flatMap { token -> listOf(prod, local).flatMap { guard(token).problems(it) + guard(token).warnings(it) } }

        assertTrue(messages.isNotEmpty())
        messages.forEach { message ->
            assertTrue("skeleton.payment.webhook.path-token" in message, message)
            assertTrue("<P>_PAYMENT_WEBHOOK_PATH_TOKEN" in message, message)
            assertFalse("SECRETSUFFIX" in message, message)
            assertFalse(monotone in message, message)
        }
        assertTrue(messages.any { "empty" in it })
        assertTrue(messages.any { "weak" in it })
    }

    @Test
    fun `the guard is named payment`() {
        assertEquals("payment", guard(strong).name)
    }
}
