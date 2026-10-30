package dev.sumin.skeleton.apikey

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.apikeytest.ApiKeyTestApplication
import dev.sumin.skeleton.apikeytest.ApiKeyTestBeans
import dev.sumin.skeleton.apikeytest.Audits
import dev.sumin.skeleton.apikeytest.Events
import dev.sumin.skeleton.common.id.SortableIds
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private val seq = AtomicInteger()
private val T0 = Instant.parse("2026-10-31T00:00:00Z")

/** 운영 경로(`admin.enabled=true`) - 권한 · 목록(필터 · 커서 · 계정 이름) · 운영 회수(사유 · 운영 기록 · 이벤트 · 멱등) */
@SpringBootTest(
    classes = [ApiKeyTestApplication::class],
    properties = [
        "skeleton.api-key.admin.enabled=true",
        "skeleton.authz.roles.ADMIN=*",
        "skeleton.authz.roles.KEYS=api-key.read",
        "skeleton.authz.roles.KEYOPS=api-key.read,api-key.revoke",
        "skeleton.authz.roles.SUPPORT=api-key.read,account.read",
    ],
)
@AutoConfigureMockMvc
@Import(ApiKeyTestBeans::class)
class ApiKeyAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var store: FakeApiKeyStore
    @Autowired lateinit var audits: Audits
    @Autowired lateinit var events: Events

    private val base = "/api/v1/admin/api-keys"

    private fun account(vararg roles: String): TestingAuthenticationToken {
        val id = "acc_adm_${seq.incrementAndGet()}_${System.nanoTime()}"
        check(accounts.insert(Account(id, "$id@example.com", true, AccountStatus.ACTIVE, roles.toSet(), null, null, null, T0, T0), emptyList()))
        return TestingAuthenticationToken(id, "n/a", *roles.map { "ROLE_$it" }.toTypedArray())
    }

    private fun key(owner: String, name: String = "ci", revokedAt: Instant? = null, expiresAt: Instant? = null): ApiKey {
        val id = SortableIds.system.newId("key_")
        val key = ApiKey(
            id, ApiKeyOwnerTypes.ACCOUNT, owner, owner, name, "h$id", "apk…abcd", setOf("notes.read"), expiresAt,
            revokedAt, revokedAt?.let { owner }, null, null, null, Instant.now(),
        )
        store.put(key)
        return key
    }

    private fun revoke(id: String, reason: String?, who: TestingAuthenticationToken) =
        mvc.perform(
            post("$base/$id/revoke").with(authentication(who)).contentType(MediaType.APPLICATION_JSON)
                .content(if (reason == null) "{}" else """{"reason":"$reason"}"""),
        )

    @Test
    fun `without api-key read the list is 403 and anonymous is 401`() {
        mvc.perform(get(base).with(authentication(account("USER")))).andExpect(status().isForbidden)
        mvc.perform(get(base)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the list is newest first in cursor pages and filters by owner and status`() {
        val owner = account("USER").name
        val other = account("USER").name
        val a = key(owner, "a"); val b = key(owner, "b"); val c = key(owner, "c", revokedAt = T0); key(other, "z")
        val admin = account("KEYS")

        val first = mvc.perform(get(base).param("ownerId", owner).param("size", "2").with(authentication(admin))).andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(2)).andExpect(jsonPath("$.values[0].id").value(c.id)).andExpect(jsonPath("$.values[1].id").value(b.id))
            .andExpect(jsonPath("$.cursor.hasNext").value(true)).andReturn().response.contentAsString
        val next = JsonPath.read<String>(first, "$.cursor.nextCursor")
        mvc.perform(get(base).param("ownerId", owner).param("size", "2").param("cursor", next).with(authentication(admin))).andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].id").value(a.id)).andExpect(jsonPath("$.cursor.hasNext").value(false))

        mvc.perform(get(base).param("ownerId", owner).param("status", "REVOKED").with(authentication(admin)))
            .andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].id").value(c.id)).andExpect(jsonPath("$.values[0].revokedBy").value("USER"))
        mvc.perform(get(base).param("ownerId", owner).param("status", "ACTIVE").with(authentication(admin)))
            .andExpect(jsonPath("$.values.length()").value(2))
        mvc.perform(get(base).param("status", "NOPE").with(authentication(admin))).andExpect(status().isBadRequest)
    }

    @Test
    fun `a row never carries the token or its hash and shows the owner name only with account read`() {
        val owner = account("USER").name
        val k = key(owner)

        val plain = mvc.perform(get(base).param("ownerId", owner).with(authentication(account("KEYS")))).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].ownerId").value(owner)).andExpect(jsonPath("$.values[0].ownerName").value(null as String?))
            .andReturn().response.contentAsString
        assertFalse(plain.contains(k.tokenHash), "hash in the list")
        assertFalse(plain.contains("tokenHash"), "hash field in the list")

        mvc.perform(get(base).param("ownerId", owner).with(authentication(account("SUPPORT"))))
            .andExpect(jsonPath("$.values[0].ownerName").value("$owner@example.com"))
    }

    @Test
    fun `revoking needs api-key revoke and a reason`() {
        val owner = account("USER").name
        val k = key(owner)

        revoke(k.id, "leaked", account("KEYS")).andExpect(status().isForbidden)
        revoke(k.id, null, account("KEYOPS")).andExpect(status().isBadRequest)
        revoke(k.id, "   ", account("KEYOPS")).andExpect(status().isBadRequest)
        assertEquals(null, store.findById(k.id)!!.revokedAt)
    }

    @Test
    fun `an operator revoke is by the operator - audited without the reason and published as an account event`() {
        val owner = account("USER").name
        val k = key(owner)
        val ops = account("KEYOPS")

        revoke(k.id, "found in a public repo", ops).andExpect(status().isNoContent)

        val stored = store.findById(k.id)!!
        assertEquals(ops.name, stored.revokedBy)
        assertEquals("found in a public repo", stored.revokedReason)
        val audit = audits.all.single { it.targetId == k.id }
        assertEquals("api-key.key.revoked", audit.action)
        assertEquals(ops.name, audit.actorId)
        assertFalse(audit.toString().contains("public repo"), "reason in the audit record")
        val event = events.all.single { it.type == AccountEventType.API_KEY_REVOKED && it.detail["keyId"] == k.id }
        assertEquals(owner, event.accountId)
        assertEquals(ops.name, event.detail["by"])
        assertFalse(event.toString().contains("public repo"), "reason in the event")
    }

    @Test
    fun `revoking again is 204 with no second audit line and an unknown key is 404`() {
        val k = key(account("USER").name)
        val ops = account("KEYOPS")

        revoke(k.id, "leaked", ops).andExpect(status().isNoContent)
        revoke(k.id, "again", ops).andExpect(status().isNoContent)
        revoke("key_missing", "leaked", ops).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("API_KEY.NOT_FOUND"))

        assertEquals(1, audits.all.count { it.targetId == k.id })
        assertEquals("leaked", store.findById(k.id)!!.revokedReason)
        assertTrue(events.all.count { it.type == AccountEventType.API_KEY_REVOKED && it.detail["keyId"] == k.id } == 1)
    }
}
