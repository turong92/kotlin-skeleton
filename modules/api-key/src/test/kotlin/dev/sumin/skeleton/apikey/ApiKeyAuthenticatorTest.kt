package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.security.BearerTokenRejectedException
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.mock.web.MockHttpServletRequest

class ApiKeyAuthenticatorTest {
    private class Clock(var now: Instant) : TimeProvider { override fun now(): Instant = now }

    private val t0 = Instant.parse("2026-10-31T00:00:00Z")
    private val clock = Clock(t0)
    private val store = FakeApiKeyStore()
    private val tokens = ApiKeyTokens("apk")
    private var block: dev.sumin.skeleton.auth.account.LoginBlock? = null
    private val accounts = object : AuthAccountRepository {
        override fun findBy(identifier: AccountIdentifier) = AuthAccount(identifier.accountId!!, "u", "u@example.com", "", setOf("ADMIN"), loginBlock = block)
    }
    private val authenticator = ApiKeyAuthenticator(store, tokens, accounts, clock, ClientIps())

    private fun issue(): String {
        val generated = tokens.generate()
        store.insert(ApiKey("key_1", "ACCOUNT", "acc_1", "acc_1", "ci", generated.hash, generated.hint, setOf("notes.read"), null, null, null, null, null, null, t0))
        return generated.token
    }

    private fun call(token: String) = authenticator.authenticate(token, MockHttpServletRequest().apply { remoteAddr = "203.0.113.7" })

    @Test
    fun `last use is written at most once a minute - a second call inside the minute leaves it, one after it moves it`() {
        val token = issue()

        call(token)
        assertEquals(t0, store.findById("key_1")!!.lastUsedAt)
        assertEquals("203.0.113.7", store.findById("key_1")!!.lastUsedIp)

        clock.now = t0.plusSeconds(30)
        call(token)
        assertEquals(t0, store.findById("key_1")!!.lastUsedAt)

        clock.now = t0.plus(Duration.ofSeconds(61))
        call(token)
        assertEquals(t0.plusSeconds(61), store.findById("key_1")!!.lastUsedAt)
    }

    @Test
    fun `the authenticated principal is the owner with the key's scopes and no roles, even for an admin owner`() {
        val auth = call(issue()) as ApiKeyAuthentication

        assertEquals("acc_1", auth.name)
        assertEquals(emptySet(), auth.principal.roles)
        assertEquals(emptyList(), auth.authorities.toList())
        assertEquals(setOf("notes.read"), auth.scopes)
        assertEquals("key_1", auth.keyId)
    }

    @Test
    fun `a blocked owner or a malformed token is rejected before anything is written`() {
        val token = issue()
        block = dev.sumin.skeleton.auth.account.LoginBlock.DELETION_PENDING

        assertEquals("API_KEY.OWNER_BLOCKED", assertFailsWith<BearerTokenRejectedException> { call(token) }.errorCode.code)
        assertEquals("API_KEY.INVALID", assertFailsWith<BearerTokenRejectedException> { call("apk_" + "a".repeat(500)) }.errorCode.code)
        assertEquals(null, store.findById("key_1")!!.lastUsedAt)
    }
}
