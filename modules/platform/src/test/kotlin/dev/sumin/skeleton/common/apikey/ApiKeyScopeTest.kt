package dev.sumin.skeleton.common.apikey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiKeyScopeTest {
    private class Handler {
        @ApiKeyScope("notes.read")
        fun read() = Unit
    }

    @Test
    fun `the annotation survives to runtime so the access interceptor can read it`() {
        val scope = Handler::class.java.getDeclaredMethod("read").getAnnotation(ApiKeyScope::class.java)
        assertEquals("notes.read", scope.value)
    }

    @Test
    fun `a declaration takes scope keys shaped like permission keys and nothing else`() {
        assertEquals(listOf("notes.read", "notes.write"), ApiKeyScopeDeclaration("notes", listOf("notes.read", "notes.write")).scopes)
        listOf("Notes.Read", "notes", "notes read", "notes.").forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { ApiKeyScopeDeclaration("notes", listOf(bad)) }
        }
    }
}
