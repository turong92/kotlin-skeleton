package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import kotlin.test.Test
import kotlin.test.assertEquals
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class MeResponseTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private fun access(vararg permissions: String) = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = permissions.toSet()
    }

    private val user = CurrentPrincipal("acc_user", "u", "u@example.com", setOf("USER"))

    @Test
    fun `permissions are always present and sorted`() {
        val body = json.readTree(json.writeValueAsString(MeResponse.of(user, access("payment.refund", "payment.read"))))
        assertEquals(listOf("payment.read", "payment.refund"), (0 until body["permissions"].size()).map { body["permissions"][it].asString() })
    }

    @Test
    fun `a caller without permissions - or an app without the checker - gets an empty list`() {
        listOf<OperatorAccess?>(access(), null).forEach {
            val body = json.readTree(json.writeValueAsString(MeResponse.of(user, it)))
            assertEquals(0, body["permissions"].size())
            assertEquals(true, body["permissions"].isArray)
        }
    }

    @Test
    fun `full authority is the wildcard as it is`() {
        val body = json.readTree(json.writeValueAsString(MeResponse.of(CurrentPrincipal("acc_a", roles = setOf("ADMIN")), access("*"))))
        assertEquals(listOf("*"), (0 until body["permissions"].size()).map { body["permissions"][it].asString() })
    }

    @Test
    fun `the other fields keep their shape - sessionId only when there is one`() {
        val without = json.readTree(json.writeValueAsString(MeResponse.of(user, access())))
        assertEquals(setOf("accountId", "username", "email", "roles", "permissions"), without.propertyNames().toSet())
        val with = json.readTree(json.writeValueAsString(MeResponse.of(user.copy(sessionId = "ses_1"), access())))
        assertEquals("ses_1", with["sessionId"].asString())
    }
}
