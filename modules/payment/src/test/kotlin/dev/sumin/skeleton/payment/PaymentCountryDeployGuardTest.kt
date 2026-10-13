package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 로컬 나라 스위치(`routing.dev-country`)는 보호 환경(stage · prod)에서 켜져 있으면 기동 실패 - 방문자의 나라를 설정이 정하게 두지 않는다. */
class PaymentCountryDeployGuardTest {
    private fun guard(devCountry: String?, devSwitch: Boolean = false) = PaymentCountryDeployGuard(PaymentProperties(routing = PaymentProperties.Routing(devCountry = devCountry, devCountrySwitch = devSwitch)))
    private val envs = mapOf("unset" to null, "local" to DeployEnv.LOCAL, "stage" to DeployEnv.STAGE, "prod" to DeployEnv.PROD)

    @Test
    fun `the switch stops stage and prod, only warns elsewhere`() {
        envs.forEach { (name, env) ->
            val context = DeployContext(env, emptySet())
            val problems = guard("US").problems(context)
            if (name == "stage" || name == "prod") assertEquals(1, problems.size, name) else assertTrue(problems.isEmpty(), name)
            assertTrue(problems.none { it.contains("US") }, "the message names the property, not the value")
        }
    }

    @Test
    fun `the per-request dev switch stops stage and prod too, and only warns elsewhere`() {
        envs.forEach { (name, env) ->
            val problems = guard(null, devSwitch = true).problems(DeployContext(env, emptySet()))
            if (name == "stage" || name == "prod") assertEquals(1, problems.size, name) else assertTrue(problems.isEmpty(), name)
        }
    }

    @Test
    fun `without the switch nothing is said`() {
        envs.values.forEach {
            assertTrue(guard(null).problems(DeployContext(it, emptySet())).isEmpty())
            assertTrue(guard(null).warnings(DeployContext(it, emptySet())).isEmpty())
        }
    }
}
