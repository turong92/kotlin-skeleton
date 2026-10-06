package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.account.LoginBlock
import dev.sumin.skeleton.sessionstest.SessionTestAccounts
import dev.sumin.skeleton.sessionstest.SessionWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.hamcrest.Matchers.hasSize
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [SessionWebTestApplication::class])
@AutoConfigureMockMvc
@Import(SessionTestAccounts::class, dev.sumin.skeleton.sessionstest.RecordingSessionEvents::class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension::class)
class SessionBodyDeliveryWebTest : SessionWebTestBase() {
    @org.springframework.beans.factory.annotation.Autowired lateinit var listener: dev.sumin.skeleton.sessionstest.RecordingSessionEventListener

    private fun refresh(token: String) =
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"$token"}"""))

    @Test
    fun `login carries a refresh token, a session id and the session id inside the jwt`() {
        val r = login()
        assertEquals(200, r.response.status)
        val sid = r.str("$.value.sessionId")!!
        assertEquals(sid, r.str("$.value.principal.sessionId"))
        assertEquals(true, r.str("$.value.refreshToken")!!.startsWith("r1."))
        assertEquals(null, r.response.getCookie("skeleton_refresh"), "body delivery sets no cookie")
    }

    @Test
    fun `refresh is public, rotates, and the replayed old token closes the session`() {
        val first = login().str("$.value.refreshToken")!!
        val second = refresh(first).andExpect(status().isOk).andReturn().str("$.value.refreshToken")!!
        assertNotEquals(first, second)

        refresh(first).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AUTH.REFRESH_REUSED"))
        refresh(second).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AUTH.REFRESH_INVALID"))
    }

    @Test
    fun `reuse reaches the listeners the app registered and leaves a WARN with no token in it`(output: org.springframework.boot.test.system.CapturedOutput) {
        val first = login().str("$.value.refreshToken")!!
        refresh(first).andExpect(status().isOk)
        refresh(first).andExpect(status().isUnauthorized)
        val seen = listener.events.filter { it.type == dev.sumin.skeleton.auth.session.SessionEventType.REFRESH_REUSE_DETECTED }
        assertEquals(1, seen.size, "the real SessionService must publish to SessionEventListener beans")
        assertEquals("acc_ann", seen.single().accountId)
        val warn = output.all.lines().single { "refresh token reuse" in it }
        assertEquals(true, "WARN" in warn && "acc_ann" in warn && first !in warn, warn)
    }

    @Test
    fun `the new access token carries the account's current roles`() {
        val token = login().str("$.value.refreshToken")!!
        accounts.roles("acc_ann", setOf("USER", "ADMIN"))
        refresh(token).andExpect(status().isOk).andExpect(jsonPath("$.value.principal.roles", hasSize<Any>(2)))
    }

    @Test
    fun `a suspended or deleted account cannot refresh`() {
        val a = login().str("$.value.refreshToken")!!
        accounts.block("acc_ann", LoginBlock.SUSPENDED)
        refresh(a).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("AUTH.ACCOUNT_SUSPENDED"))

        val b = login("bob@example.com").str("$.value.refreshToken")!!
        accounts.remove("acc_bob")
        refresh(b).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AUTH.REFRESH_INVALID"))
    }

    @Test
    fun `logout is always 204 and kills the token`() {
        val t = login().str("$.value.refreshToken")!!
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"$t"}""")).andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"nonsense"}""")).andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent)
        refresh(t).andExpect(status().isUnauthorized)
    }

    @Test
    fun `sessions need a bearer token, show device info and mark the current one`() {
        mvc.perform(get("/api/v1/auth/sessions")).andExpect(status().isUnauthorized)
        login(ua = "Other-UA", ip = "198.51.100.1")
        val me = login(ua = "My-UA", ip = "203.0.113.50")
        val bearer = "Bearer " + me.str("$.value.accessToken")
        val list = mvc.perform(get("/api/v1/auth/sessions").header("Authorization", bearer)).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[?(@.current==true)].userAgent").value("My-UA"))
            .andExpect(jsonPath("$.values[?(@.current==true)].ip").value("203.0.113.50"))
            .andExpect(jsonPath("$.values[?(@.current==true)].deviceName").value("Test phone"))
            .andReturn()
        val other = com.jayway.jsonpath.JsonPath.read<List<String>>(list.response.contentAsString, "$.values[?(@.current==false)].id").single()

        mvc.perform(delete("/api/v1/auth/sessions/$other").header("Authorization", bearer)).andExpect(status().isNoContent)
        mvc.perform(delete("/api/v1/auth/sessions/$other").header("Authorization", bearer)).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("AUTH.SESSION_NOT_FOUND"))
    }

    @Test
    fun `another account's session cannot be revoked and looks missing`() {
        val bobSid = login("bob@example.com").str("$.value.sessionId")!!
        val ann = "Bearer " + login().str("$.value.accessToken")
        mvc.perform(delete("/api/v1/auth/sessions/$bobSid").header("Authorization", ann)).andExpect(status().isNotFound)
    }

    @Test
    fun `revoking all keeps the current session by default`() {
        val old = login().str("$.value.refreshToken")!!
        val cur = login()
        mvc.perform(delete("/api/v1/auth/sessions").header("Authorization", "Bearer " + cur.str("$.value.accessToken"))).andExpect(status().isNoContent)
        refresh(old).andExpect(status().isUnauthorized)
        refresh(cur.str("$.value.refreshToken")!!).andExpect(status().isOk)
    }

    @Test
    fun `login still works exactly as before for the wrong password`() {
        val r = login(password = "nope")
        assertEquals(401, r.response.status)
        assertNull(r.response.getCookie("skeleton_refresh"))
    }
}
