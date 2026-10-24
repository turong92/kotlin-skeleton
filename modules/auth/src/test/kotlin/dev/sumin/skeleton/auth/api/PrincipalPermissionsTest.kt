package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import kotlin.test.Test
import kotlin.test.assertEquals
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** 같은 principal 모양을 `/auth/me` · 로그인 · 갱신이 돌려준다 — `permissions` 는 늘 있고 사전순, 전권이면 `["*"]` */
class PrincipalPermissionsTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val user = CurrentPrincipal("acc_user", "u", "u@example.com", setOf("USER"))

    private fun access(vararg permissions: String) = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = permissions.toSet()
    }

    private fun permissions(p: CurrentPrincipal): List<String> =
        json.readTree(json.writeValueAsString(p)).let { n -> (0 until n["permissions"].size()).map { n["permissions"][it].asString() } }

    @Test
    fun `permissions are sorted and full authority is the wildcard as it is`() {
        assertEquals(listOf("payment.read", "payment.refund"), permissions(user.withPermissions(access("payment.refund", "payment.read"))))
        assertEquals(listOf("*"), permissions(user.withPermissions(access("*"))))
    }

    @Test
    fun `without permissions - or without the checker - the key is an empty list`() {
        listOf<OperatorAccess?>(access(), null).forEach { assertEquals(emptyList(), permissions(user.withPermissions(it))) }
    }

    @Test
    fun `a principal read from a token has no permissions key at all`() {
        assertEquals(false, json.readTree(json.writeValueAsString(user)).has("permissions"))
    }

    @Test
    fun `login and refresh responses carry the permissions of the signed-in account`() {
        val jwt = JwtTokenService(AuthProperties.Jwt(secret = "a-test-secret-that-is-at-least-32-bytes-long"))
        val account = AuthAccount("acc_user", "u", "u@example.com", "hash", setOf("USER"))
        val factory = AuthTokenResponseFactory(jwt, { access("payment.read") })
        assertEquals(listOf("payment.read"), factory.issue(account).principal.permissions)
        assertEquals(listOf("payment.read"), factory.issueWith(account, null).principal.permissions)
        assertEquals(emptyList(), AuthTokenResponseFactory(jwt).issue(account).principal.permissions)
    }
}
