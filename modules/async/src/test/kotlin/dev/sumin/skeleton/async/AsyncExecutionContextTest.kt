package dev.sumin.skeleton.async

import dev.sumin.skeleton.common.TraceIdFilter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.slf4j.MDC
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class AsyncExecutionContextTest {
    @AfterTest
    fun tearDown() {
        MDC.clear()
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `snapshot restores trace run account and security context around runnable`() {
        MDC.put(TraceIdFilter.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736")
        MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, "00f067aa0ba902b7")
        MDC.put(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY, "1111111111111111")
        MDC.put(AsyncMdcKeys.RUN_ID, "run-123")
        MDC.put(AsyncMdcKeys.ACCOUNT_ID, "acc-user")
        val authentication = UsernamePasswordAuthenticationToken("acc-user", "n/a")
        SecurityContextHolder.getContext().authentication = authentication

        val snapshot = AsyncExecutionContext.capture()
        MDC.clear()
        MDC.put("caller", "restored-after-run")
        SecurityContextHolder.clearContext()

        var seenTraceId: String? = null
        var seenRunId: String? = null
        var seenAccountId: String? = null
        var seenAuthentication: Any? = null

        snapshot.wrap(
            Runnable {
                seenTraceId = MDC.get(TraceIdFilter.MDC_KEY)
                seenRunId = MDC.get(AsyncMdcKeys.RUN_ID)
                seenAccountId = MDC.get(AsyncMdcKeys.ACCOUNT_ID)
                seenAuthentication = SecurityContextHolder.getContext().authentication
            },
        ).run()

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", seenTraceId)
        assertEquals("run-123", seenRunId)
        assertEquals("acc-user", seenAccountId)
        assertEquals(authentication, seenAuthentication)
        assertEquals("restored-after-run", MDC.get("caller"))
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `task decorator captures context when task is submitted`() {
        MDC.put(TraceIdFilter.MDC_KEY, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        MDC.put(AsyncMdcKeys.RUN_ID, "run-submit")
        val authentication = UsernamePasswordAuthenticationToken("submit-user", "n/a")
        SecurityContextHolder.getContext().authentication = authentication

        val decorated = AsyncContextTaskDecorator().decorate(
            Runnable {
                assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", MDC.get(TraceIdFilter.MDC_KEY))
                assertEquals("run-submit", MDC.get(AsyncMdcKeys.RUN_ID))
                assertEquals(authentication, SecurityContextHolder.getContext().authentication)
            },
        )

        MDC.clear()
        SecurityContextHolder.clearContext()

        decorated.run()

        assertNull(MDC.get(TraceIdFilter.MDC_KEY))
        assertNull(SecurityContextHolder.getContext().authentication)
    }
}
