package dev.sumin.skeleton.authorprofile

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class AuthorProfilePropertiesTest {
    private fun bind(vararg pairs: Pair<String, String>): AuthorProfileProperties =
        Binder(MapConfigurationPropertySource(mapOf(*pairs))).bindOrCreate("skeleton.author-profile", AuthorProfileProperties::class.java)

    @Test
    fun `defaults are five profiles, five creations per thirty days, adoption on, http on, admin off`() {
        val p = bind()
        assertEquals(5, p.maxPerAccount)
        assertEquals(5, p.createLimit)
        assertEquals(Duration.ofDays(30), p.createWindow)
        assertTrue(p.adoptOnStartup)
        assertTrue(p.http.enabled)
        assertEquals("/api/v1/author-profiles", p.http.basePath)
        assertFalse(p.admin.enabled)
    }

    @Test
    fun `every key binds`() {
        val p = bind(
            "skeleton.author-profile.max-per-account" to "9",
            "skeleton.author-profile.create-limit" to "0",
            "skeleton.author-profile.create-window" to "7d",
            "skeleton.author-profile.adopt-on-startup" to "false",
            "skeleton.author-profile.http.enabled" to "false",
            "skeleton.author-profile.http.base-path" to "/api/v1/profiles",
            "skeleton.author-profile.admin.enabled" to "true",
        )
        assertEquals(9, p.maxPerAccount)
        assertEquals(0, p.createLimit)
        assertEquals(Duration.ofDays(7), p.createWindow)
        assertFalse(p.adoptOnStartup)
        assertFalse(p.http.enabled)
        assertEquals("/api/v1/profiles", p.http.basePath)
        assertTrue(p.admin.enabled)
    }

    @Test
    fun `nonsense values fail at startup with the key in the message`() {
        fun message(vararg pairs: Pair<String, String>) = assertFailsWith<Exception> { bind(*pairs) }.let { e -> generateSequence<Throwable>(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() } }
        assertTrue("max-per-account" in message("skeleton.author-profile.max-per-account" to "0"))
        assertTrue("create-limit" in message("skeleton.author-profile.create-limit" to "-1"))
        assertTrue("create-window" in message("skeleton.author-profile.create-window" to "0s"))
        assertTrue("base-path" in message("skeleton.author-profile.http.base-path" to "profiles"))
    }
}
