package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.sessionstest.SessionTestAccounts
import dev.sumin.skeleton.sessionstest.SessionWebTestApplication
import jakarta.servlet.http.Cookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [SessionWebTestApplication::class], properties = ["skeleton.auth-session.delivery=cookie"])
@AutoConfigureMockMvc
@Import(SessionTestAccounts::class)
class SessionCookieDeliveryWebTest : SessionWebTestBase() {
    private fun cookieOf(r: org.springframework.test.web.servlet.MvcResult): Cookie = assertNotNull(r.response.getCookie("skeleton_refresh"))

    @Test
    fun `login sets an HttpOnly Secure SameSite=Strict cookie scoped to the auth path and keeps the token out of the JSON`() {
        val r = login()
        val c = cookieOf(r)
        assertTrue(c.isHttpOnly)
        assertTrue(c.secure)
        assertEquals("/api/v1/auth", c.path)
        assertTrue(r.response.getHeader("Set-Cookie")!!.contains("SameSite=Strict"))
        assertNull(r.str("$.value.refreshToken"), "cookie delivery must not echo the token into the body")
        assertNotNull(r.str("$.value.sessionId"))
    }

    @Test
    fun `refresh without the CSRF header is refused even with a valid cookie`() {
        val c = cookieOf(login())
        mvc.perform(post("/api/v1/auth/refresh").cookie(c)).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("AUTH.CSRF_HEADER_REQUIRED"))
    }

    @Test
    fun `refresh with the cookie and the header rotates the cookie`() {
        val c = cookieOf(login())
        val r = mvc.perform(post("/api/v1/auth/refresh").cookie(c).header("X-Requested-With", "fetch")).andExpect(status().isOk).andReturn()
        assertNotEquals(c.value, cookieOf(r).value)
        // the old cookie is now a replay
        mvc.perform(post("/api/v1/auth/refresh").cookie(c).header("X-Requested-With", "fetch")).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH.REFRESH_REUSED"))
    }

    @Test
    fun `a refresh token in the body is ignored in cookie mode`() {
        val t = cookieOf(login()).value
        mvc.perform(post("/api/v1/auth/refresh").header("X-Requested-With", "fetch").contentType("application/json").content("""{"refreshToken":"$t"}"""))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AUTH.REFRESH_INVALID"))
    }

    @Test
    fun `logout clears the cookie`() {
        val c = cookieOf(login())
        val r = mvc.perform(post("/api/v1/auth/logout").cookie(c).header("X-Requested-With", "fetch")).andExpect(status().isNoContent).andReturn()
        assertEquals(0, cookieOf(r).maxAge)
        mvc.perform(post("/api/v1/auth/refresh").cookie(c).header("X-Requested-With", "fetch")).andExpect(status().isUnauthorized)
    }
}
