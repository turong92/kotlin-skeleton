package dev.sumin.skeleton.common.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadedKeyUsagesTest {
    @Test
    fun `the union says a key is in use when any implementation says so, and none means no answer at all`() {
        val board = UploadedKeyUsage { keys -> keys.filter { it.startsWith("b/") }.toSet() }
        val app = UploadedKeyUsage { keys -> keys.filter { it.startsWith("a/") }.toSet() }
        val union = UploadedKeyUsages.union(listOf(board, app))!!
        assertEquals(setOf("b/1", "a/2"), union.inUse(listOf("b/1", "a/2", "x/3")))
        assertNull(UploadedKeyUsages.union(emptyList()), "without any source cleanup deletes nothing")
    }
}
