package dev.sumin.skeleton.apikey

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.apikeytest.ApiKeyTestApplication
import dev.sumin.skeleton.apikeytest.ApiKeyTestBeans
import dev.sumin.skeleton.apikeytest.Events
import dev.sumin.skeleton.apikeytest.Mails
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private val ips = AtomicInteger()
private const val PASSWORD = "tangerine-42-moon"

/** 진짜 account · auth 위에서 - 발급(다시 인증) · 내 목록 · 본인 회수 · 키로 닿는 길(표시 · 범위) · 요청 인증의 거절들 · 탈퇴 · 토큰 원문이 어디에도 없음 */
@SpringBootTest(
    classes = [ApiKeyTestApplication::class],
    properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4"],
)
@AutoConfigureMockMvc
@Import(ApiKeyTestBeans::class)
class ApiKeyWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var events: Events
    @Autowired lateinit var store: FakeApiKeyStore
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var erasures: List<AccountErasureListener>

    private class User(val id: String, val bearer: String)

    private fun json(body: String) = MediaType.APPLICATION_JSON to body

    private fun signUp(): User {
        val email = "k${System.nanoTime()}@example.com"
        val ip = "198.51.100.${100 + ips.getAndIncrement() % 100}"
        val sign = mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$PASSWORD"}""")).andExpect(status().isAccepted).andReturn()
        val signUpId = JsonPath.read<String>(sign.response.contentAsString, "$.value.signUpId")
        val done = mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"$signUpId","code":"${mails.lastCode()}"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        return User(JsonPath.read(done, "$.value.principal.accountId"), "Bearer " + JsonPath.read<String>(done, "$.value.accessToken"))
    }

    private fun create(user: User, name: String = "ci", scopes: String = """["notes.read"]""", proof: String = """"currentPassword":"$PASSWORD"""", extra: String = ""): ResultActions =
        mvc.perform(post("/api/v1/api-keys").header("Authorization", user.bearer).contentType(MediaType.APPLICATION_JSON).content("""{"name":"$name","scopes":$scopes,$proof$extra}"""))

    private fun issue(user: User, scopes: String = """["notes.read"]"""): Pair<String, String> {
        val body = create(user, scopes = scopes).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read<String>(body, "$.value.key.id") to JsonPath.read<String>(body, "$.value.token")
    }

    private fun withKey(token: String, method: String = "GET", path: String) =
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path).header("Authorization", "Bearer $token"))

    @Test
    fun `issuing needs a fresh proof - without the password nothing is created`() {
        val user = signUp()

        create(user, proof = """"currentPassword":"wrong-password-1"""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))
        create(user, proof = """"name2":"x"""").andExpect(status().isBadRequest)

        assertTrue(store.all().none { it.ownerId == user.id })
    }

    @Test
    fun `the token is shown once with no-store and only its hash is kept`() {
        val user = signUp()

        val created = create(user, name = "ci job").andExpect(status().isCreated).andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsString
        val token = JsonPath.read<String>(created, "$.value.token")
        val key = store.all().single { it.ownerId == user.id }

        assertTrue(Regex("^apk_[0-9A-Za-z]{32}$").matches(token), token)
        assertEquals(64, key.tokenHash.length)
        assertFalse(key.toString().contains(token) || key.tokenHash == token || key.tokenHint.contains(token))
        assertEquals("apk…${token.takeLast(4)}", key.tokenHint)
        assertEquals("ci job", JsonPath.read<String>(created, "$.value.key.name"))
        val listed = mvc.perform(get("/api/v1/api-keys").header("Authorization", user.bearer)).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse(listed.contains(token) || listed.contains(key.tokenHash), "the list must not carry the token or its hash")
    }

    @Test
    fun `scopes must be declared by a module or the app, and the declared ones are listed`() {
        val user = signUp()

        create(user, scopes = """["notes.read","nope.write"]""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("API_KEY.SCOPE_UNKNOWN"))
        create(user, scopes = "[]").andExpect(status().isBadRequest)

        val scopes = mvc.perform(get("/api/v1/api-keys/scopes").header("Authorization", user.bearer)).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(listOf("billing.read", "notes.read", "notes.write"), JsonPath.read<List<String>>(scopes, "$.values[*].key"))
        assertEquals("billing", JsonPath.read<String>(scopes, "$.values[0].module"))
    }

    @Test
    fun `a key reaches only marked handlers whose scope it holds, as its owner with no roles`() {
        val user = signUp()
        val (_, token) = issue(user)

        withKey(token, path = "/api/v1/probe/read").andExpect(status().isOk).andExpect(jsonPath("$.value.name").value(user.id)).andExpect(jsonPath("$.value.roles").isEmpty)
        withKey(token, "POST", "/api/v1/probe/write").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("API_KEY.SCOPE_MISSING")).andExpect(jsonPath("$.data.scope").value("notes.write"))
        withKey(token, path = "/api/v1/probe/unmarked").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("API_KEY.NOT_ALLOWED"))
        withKey(token, path = "/api/v1/account/me").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("API_KEY.NOT_ALLOWED"))
        withKey(token, path = "/api/v1/api-keys").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("API_KEY.NOT_ALLOWED"))
        withKey(token, "POST", "/api/v1/api-keys").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("API_KEY.NOT_ALLOWED"))
        withKey(token, path = "/api/v1/admin/accounts").andExpect(status().isForbidden)
        assertEquals(1, store.all().count { it.ownerId == user.id })
    }

    @Test
    fun `a request with a signed-in user token is unchanged by the hook`() {
        val user = signUp()

        mvc.perform(get("/api/v1/probe/unmarked").header("Authorization", user.bearer)).andExpect(status().isOk).andExpect(jsonPath("$.value.name").value(user.id))
        mvc.perform(get("/api/v1/probe/unmarked").header("Authorization", "Bearer not.a.token")).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("COMMON.UNAUTHORIZED"))
    }

    @Test
    fun `unknown, revoked and expired keys are 401 with their own code`() {
        val user = signUp()
        val (id, token) = issue(user)
        val (_, soonGone) = issue(user)

        withKey("apk_" + "x".repeat(32), path = "/api/v1/probe/read").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("API_KEY.INVALID"))
        withKey(token, path = "/api/v1/probe/read").andExpect(status().isOk)

        mvc.perform(delete("/api/v1/api-keys/$id").header("Authorization", user.bearer)).andExpect(status().isNoContent)
        withKey(token, path = "/api/v1/probe/read").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("API_KEY.REVOKED"))

        val key = store.all().single { it.tokenHash == ApiKeyTokens("apk").hash(soonGone) }
        store.put(key.copy(expiresAt = Instant.now().minusSeconds(60)))
        withKey(soonGone, path = "/api/v1/probe/read").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("API_KEY.EXPIRED"))
    }

    @Test
    fun `a key whose owner is suspended is 403 OWNER_BLOCKED`() {
        val user = signUp()
        val (_, token) = issue(user)
        withKey(token, path = "/api/v1/probe/read").andExpect(status().isOk)

        accounts.update(user.id, AccountPatch(status = AccountStatus.SUSPENDED), Instant.now())

        withKey(token, path = "/api/v1/probe/read").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("API_KEY.OWNER_BLOCKED"))
    }

    @Test
    fun `revoking is the owner's own - someone else's key is a 404 and stays active`() {
        val owner = signUp()
        val other = signUp()
        val (id, token) = issue(owner)

        mvc.perform(delete("/api/v1/api-keys/$id").header("Authorization", other.bearer)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("API_KEY.NOT_FOUND"))
        mvc.perform(delete("/api/v1/api-keys/key_missing").header("Authorization", owner.bearer)).andExpect(status().isNotFound)

        withKey(token, path = "/api/v1/probe/read").andExpect(status().isOk)
    }

    @Test
    fun `my list holds my keys only, newest first, with the status and who revoked`() {
        val user = signUp()
        val other = signUp()
        val (first, _) = issue(user)
        val (second, _) = issue(user, scopes = """["notes.read","notes.write"]""")
        issue(other)
        mvc.perform(delete("/api/v1/api-keys/$first").header("Authorization", user.bearer)).andExpect(status().isNoContent)

        val body = mvc.perform(get("/api/v1/api-keys").header("Authorization", user.bearer)).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(listOf(second, first), JsonPath.read<List<String>>(body, "$.values[*].id"))
        assertEquals(listOf("ACTIVE", "REVOKED"), JsonPath.read<List<String>>(body, "$.values[*].status"))
        assertEquals("USER", JsonPath.read<String>(body, "$.values[1].revokedBy"))
        assertEquals(listOf("notes.read", "notes.write"), JsonPath.read<List<String>>(body, "$.values[0].scopes"))
    }

    @Test
    fun `an owner holds at most ten active keys - a revoked one frees a place`() {
        val user = signUp()
        val ids = (1..10).map { issue(user).first }

        create(user).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("API_KEY.LIMIT_REACHED"))
        mvc.perform(delete("/api/v1/api-keys/${ids.first()}").header("Authorization", user.bearer)).andExpect(status().isNoContent)
        create(user).andExpect(status().isCreated)
    }

    @Test
    fun `account erasure deletes the keys the account owned and nobody else's`() {
        val gone = signUp()
        val stays = signUp()
        issue(gone); issue(gone); issue(stays)

        erasures.single { it.name == "api-key" }.erase(ErasureRequest(gone.id, "deleted:x"))

        assertEquals(0, store.all().count { it.ownerId == gone.id })
        assertEquals(1, store.all().count { it.ownerId == stays.id })
    }

    @Test
    fun `issuing and revoking one's own key publish account events with the key id and who, and the operator paths are 404 while admin is off`() {
        val user = signUp()
        val (id, _) = issue(user)
        mvc.perform(delete("/api/v1/api-keys/$id").header("Authorization", user.bearer)).andExpect(status().isNoContent)
        mvc.perform(delete("/api/v1/api-keys/$id").header("Authorization", user.bearer)).andExpect(status().isNoContent)

        val mine = events.all.filter { it.detail["keyId"] == id }
        assertEquals(listOf(AccountEventType.API_KEY_CREATED, AccountEventType.API_KEY_REVOKED), mine.map { it.type })
        assertTrue(mine.all { it.accountId == user.id && it.detail["by"] == user.id })

        mvc.perform(get("/api/v1/admin/api-keys").header("Authorization", user.bearer)).andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/admin/api-keys/$id/revoke").header("Authorization", user.bearer).contentType(MediaType.APPLICATION_JSON).content("""{"reason":"x"}""")).andExpect(status().isNotFound)
    }

    @Test
    fun `the raw token is in no log line and no account event`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        try {
            val user = signUp()
            val (id, token) = issue(user)
            withKey(token, path = "/api/v1/probe/read").andExpect(status().isOk)
            withKey(token, "POST", "/api/v1/probe/write").andExpect(status().isForbidden)
            withKey(token, path = "/api/v1/probe/unmarked").andExpect(status().isForbidden)
            mvc.perform(delete("/api/v1/api-keys/$id").header("Authorization", user.bearer)).andExpect(status().isNoContent)
            withKey(token, path = "/api/v1/probe/read").andExpect(status().isUnauthorized)

            val hash = ApiKeyTokens("apk").hash(token)
            val logs = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
            assertFalse(logs.contains(token), "token in a log line")
            assertFalse(logs.contains(hash), "token hash in a log line")
            assertFalse(events.all.joinToString("\n") { it.toString() }.contains(token), "token in an account event")
        } finally {
            root.detachAppender(appender)
        }
    }
}
