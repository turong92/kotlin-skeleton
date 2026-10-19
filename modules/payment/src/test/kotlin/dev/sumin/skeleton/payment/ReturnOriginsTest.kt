package dev.sumin.skeleton.payment

import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnOriginsTest {
    private val configured = "https://app.example.com/payments/return?lang=ko"
    private val allowed = setOf("http://localhost:5173", "https://dev.example.com/")

    @Test
    fun `an origin on the allow list replaces the scheme and host of the configured address and keeps its path and query`() {
        assertEquals("http://localhost:5173/payments/return?lang=ko", ReturnOrigins.resolve(configured, "http://localhost:5173", allowed))
        assertEquals("https://dev.example.com/payments/return?lang=ko", ReturnOrigins.resolve(configured, "https://DEV.example.com", allowed))
    }

    @Test
    fun `an origin outside the allow list falls back to the configured address - no open redirect`() {
        assertEquals(configured, ReturnOrigins.resolve(configured, "https://evil.example.net", allowed))
        assertEquals(configured, ReturnOrigins.resolve(configured, "http://localhost:5174", allowed))
        assertEquals(configured, ReturnOrigins.resolve(configured, "https://dev.example.com.evil.net", allowed))
    }

    @Test
    fun `anything that is not a bare origin falls back, even when it starts with an allowed one`() {
        listOf(
            "http://localhost:5173/path", "http://localhost:5173@evil.example.net", "http://localhost:5173?x=1", "http://localhost:5173#f",
            "javascript:alert(1)", "//evil.example.net", "", "  ", "http://localhost:5173\\@evil.example.net", "http://localhost:5173 ",
        ).forEach { assertEquals(configured, ReturnOrigins.resolve(configured, it, allowed), it) }
    }

    @Test
    fun `no origin or an empty allow list keeps today's behavior`() {
        assertEquals(configured, ReturnOrigins.resolve(configured, null, allowed))
        assertEquals(configured, ReturnOrigins.resolve(configured, "http://localhost:5173", emptySet()))
    }
}
