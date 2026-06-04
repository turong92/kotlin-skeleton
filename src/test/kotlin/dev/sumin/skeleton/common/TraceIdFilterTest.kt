package dev.sumin.skeleton.common

import kotlin.test.Test
import kotlin.test.assertEquals

class TraceIdFilterTest {
    @Test
    fun `log context omits parent span when absent`() {
        assertEquals(
            "[traceId=0123456789abcdef0123456789abcdef spanId=abcdef0123456789]",
            TraceIdFilter.formatLogContext(
                traceId = "0123456789abcdef0123456789abcdef",
                spanId = "abcdef0123456789",
                parentSpanId = null,
            ),
        )
    }

    @Test
    fun `log context includes parent span when present`() {
        assertEquals(
            "[traceId=0123456789abcdef0123456789abcdef spanId=abcdef0123456789 parentSpanId=1111111111111111]",
            TraceIdFilter.formatLogContext(
                traceId = "0123456789abcdef0123456789abcdef",
                spanId = "abcdef0123456789",
                parentSpanId = "1111111111111111",
            ),
        )
    }
}
