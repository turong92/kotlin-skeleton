package dev.sumin.skeleton.authorprofile.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.authorprofile.AuthorProfileService
import dev.sumin.skeleton.authorprofile.T0
import dev.sumin.skeleton.authorprofiletest.AuthorProfileWebTestApplication
import dev.sumin.skeleton.authorprofiletest.FakeAuthorProfileStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class RecordedAccountEvents : AccountEventListener {
    val events = CopyOnWriteArrayList<AccountEvent>()
    override fun on(event: AccountEvent) { events += event }
}

@TestConfiguration(proxyBeanMethods = false)
class RecordingEvents {
    @Bean fun recordedAccountEvents() = RecordedAccountEvents()
}

/** 관리자의 연결 조회(스펙 §7.9) — ADMIN 만, 호출마다 `AUTHOR_LINK_VIEWED`, detail · 로그에 프로필 id 없음 */
@SpringBootTest(classes = [AuthorProfileWebTestApplication::class], properties = ["skeleton.author-profile.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(FakeAuthorProfileStore::class, RecordingEvents::class)
class AuthorProfileAdminWebTest : WebTestSupport() {
    @Autowired lateinit var service: AuthorProfileService
    @Autowired lateinit var recorded: RecordedAccountEvents

    private val base = "/api/v1/admin/author-profiles"
    private val seq = AtomicInteger()
    private val logs = ListAppender<ILoggingEvent>()
    private val root get() = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeTest fun captureLogs() { logs.start(); root.addAppender(logs) }

    @AfterTest fun releaseLogs() { root.detachAppender(logs); logs.stop() }

    private fun logged(): List<String> = logs.list.map { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }

    private fun account(vararg roles: String, status: AccountStatus = AccountStatus.ACTIVE): TestingAuthenticationToken {
        val id = "acc_adm_${seq.incrementAndGet()}_${System.nanoTime()}"
        val account = Account(id, "$id@example.com", true, status, roles.toSet(), null, null, null, T0, T0)
        check(accounts.insert(account, emptyList()))
        return TestingAuthenticationToken(id, "n/a", *roles.map { "ROLE_$it" }.toTypedArray())
    }

    private fun admin() = account("USER", "ADMIN")

    /** 대상 계정: 첫 프로필 + 둘째 + 셋째(지움). 돌려주는 것은 (계정 id, 프로필 id 들) */
    private fun target(): Pair<String, List<String>> {
        val who = caller("Mina")
        val first = service.ensureFirstProfile(who.name).id
        val second = service.create(who.name, "Second").id
        val third = service.create(who.name, "Third").id
        service.delete(who.name, third)
        return who.name to listOf(first, second, third)
    }

    @Test
    fun `ADMIN reads the profiles of a profile's account - deleted included, with times`() {
        val (acc, ids) = target()
        call(get("$base/${ids[1]}"), admin()).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.accountId").value(acc))
            .andExpect(jsonPath("$.value.profiles.length()").value(3))
            .andExpect(jsonPath("$.value.profiles[0].id").value(ids[0])).andExpect(jsonPath("$.value.profiles[0].name").value("Mina"))
            .andExpect(jsonPath("$.value.profiles[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.value.profiles[0].createdAt").exists()).andExpect(jsonPath("$.value.profiles[0].updatedAt").exists())
            .andExpect(jsonPath("$.value.profiles[0].deletedAt").doesNotExist())
            .andExpect(jsonPath("$.value.profiles[2].id").value(ids[2])).andExpect(jsonPath("$.value.profiles[2].status").value("DELETED"))
            .andExpect(jsonPath("$.value.profiles[2].name").doesNotExist()).andExpect(jsonPath("$.value.profiles[2].deletedAt").exists())
    }

    @Test
    fun `a profile has exactly id name tag status createdAt updatedAt deletedAt and the response carries no email`() {
        val (acc, ids) = target()
        val body = text(call(get("$base/${ids[0]}"), admin()).andExpect(status().isOk))
        val first = com.jayway.jsonpath.JsonPath.read<Map<String, Any?>>(body, "$.value.profiles[0]")
        assertEquals(setOf("id", "name", "tag", "status", "createdAt", "updatedAt", "deletedAt"), first.keys)
        assertFalse("@example.com" in body, "no email in the response")
        assertTrue(acc in body)
    }

    @Test
    fun `ADMIN reads an account's profiles by accountId`() {
        val (acc, ids) = target()
        call(get(base).param("accountId", acc), admin()).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.accountId").value(acc)).andExpect(jsonPath("$.value.profiles.length()").value(3))
            .andExpect(jsonPath("$.value.profiles[1].id").value(ids[1]))
    }

    @Test
    fun `an existing account with no profiles is an empty list and is audited`() {
        val who = caller()
        call(get(base).param("accountId", who.name), admin()).andExpect(status().isOk).andExpect(jsonPath("$.value.profiles.length()").value(0))
        assertEquals(1, recorded.events.count { it.type == AccountEventType.AUTHOR_LINK_VIEWED && it.accountId == who.name })
    }

    @Test
    fun `a MODERATOR a plain user a stale ADMIN token and a suspended admin are 403 on both paths and nothing is audited`() {
        val (acc, ids) = target()
        val before = recorded.events.size
        call(get("$base/${ids[0]}"), account("USER", "MODERATOR")).andExpect(status().isForbidden)
        call(get(base).param("accountId", acc), account("USER", "MODERATOR")).andExpect(status().isForbidden)
        call(get("$base/${ids[0]}"), caller()).andExpect(status().isForbidden)
        call(get(base).param("accountId", acc), caller()).andExpect(status().isForbidden)
        // 토큰은 ADMIN 이라고 하지만 저장소의 계정은 USER 뿐 — 매 호출 저장소를 다시 읽는다
        val stale = caller().let { TestingAuthenticationToken(it.name, "n/a", "ROLE_USER", "ROLE_ADMIN") }
        call(get("$base/${ids[0]}"), stale).andExpect(status().isForbidden)
        call(get("$base/${ids[0]}"), account("USER", "ADMIN", status = AccountStatus.SUSPENDED)).andExpect(status().isForbidden)
        call(get(base).param("accountId", acc), account("USER", "ADMIN", status = AccountStatus.SUSPENDED)).andExpect(status().isForbidden)
        assertEquals(before, recorded.events.size)
    }

    @Test
    fun `a deleted profile id still resolves its account - 200 and exactly one audit event`() {
        val (acc, ids) = target()
        val boss = admin()
        recorded.events.clear()
        call(get("$base/${ids[2]}"), boss).andExpect(status().isOk).andExpect(jsonPath("$.value.accountId").value(acc))
        assertEquals(1, recorded.events.count { it.type == AccountEventType.AUTHOR_LINK_VIEWED && it.accountId == acc })
    }

    @Test
    fun `an anonymous call is 401`() {
        call(get("$base/prf_x")).andExpect(status().isUnauthorized)
        call(get(base).param("accountId", "acc_x")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an unknown profile id is 404 and an unknown account id is 404 and neither is audited`() {
        val boss = admin()
        val before = recorded.events.size
        call(get("$base/prf_00000000000000000000000000000000"), boss).andExpect(status().isNotFound)
        call(get(base).param("accountId", "acc_nobody"), boss).andExpect(status().isNotFound)
        assertEquals(before, recorded.events.size, "a miss leaves no event, so a probe cannot tell anything from the audit trail")
    }

    @Test
    fun `accountId is required`() {
        call(get(base), admin()).andExpect(status().isBadRequest)
    }

    @Test
    fun `every call publishes AUTHOR_LINK_VIEWED for the target account by the admin with no profile id in detail`() {
        val (acc, ids) = target()
        val boss = admin()
        recorded.events.clear()
        call(get("$base/${ids[0]}"), boss).andExpect(status().isOk)
        call(get(base).param("accountId", acc), boss).andExpect(status().isOk)
        val viewed = recorded.events.filter { it.type == AccountEventType.AUTHOR_LINK_VIEWED }
        assertEquals(2, viewed.size)
        viewed.forEach {
            assertEquals(acc, it.accountId)
            assertEquals(mapOf("by" to boss.name), it.detail)
            assertTrue(ids.none { id -> id in it.toString() }, "the event carries no profile id")
        }
    }

    @Test
    fun `no log line carries a profile id together with its account id`() {
        val (acc, ids) = target()
        call(get("$base/${ids[1]}"), admin()).andExpect(status().isOk)
        call(get(base).param("accountId", acc), admin()).andExpect(status().isOk)
        val lines = logged()
        assertTrue(lines.any { "AUTHOR_LINK_VIEWED" in it }, "the audit line is logged: $lines")
        assertTrue(lines.any { ids[1] in it }, "the request line carrying the profile id was captured, so the pair check below looks at real output: $lines")
        lines.forEach { line -> assertFalse(acc in line && ids.any { it in line }, "pair in log line: $line") }
        lines.filter { "AUTHOR_LINK_VIEWED" in it }.forEach { line -> assertTrue(ids.none { it in line }, "profile id in the audit line: $line") }
    }
}
