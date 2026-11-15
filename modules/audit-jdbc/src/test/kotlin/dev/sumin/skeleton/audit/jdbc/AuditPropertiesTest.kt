package dev.sumin.skeleton.audit.jdbc

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AuditPropertiesTest {
    @Test
    fun `by default nothing is deleted and the HTTP path is off`() {
        val defaults = AuditProperties()
        assertNull(defaults.retention)
        assertFalse(defaults.admin.enabled)
    }

    @Test
    fun `a keep time shorter than the thirty days the database enforces fails at startup, thirty days and more are fine`() {
        assertFailsWith<IllegalArgumentException> { AuditProperties(retention = Duration.ofDays(29)) }
        assertFailsWith<IllegalArgumentException> { AuditProperties(retention = Duration.ZERO) }
        assertEquals(Duration.ofDays(30), AuditProperties(retention = Duration.ofDays(30)).retention)
        assertEquals(Duration.ofDays(365), AuditProperties(retention = Duration.ofDays(365)).retention)
    }
}
