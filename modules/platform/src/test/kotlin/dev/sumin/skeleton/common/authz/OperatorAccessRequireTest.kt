package dev.sumin.skeleton.common.authz

import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

class OperatorAccessRequireTest {
    private var computed = 0
    private val access = object : AbstractOperatorAccess() {
        override fun compute(ref: OperatorRef): Set<String> { computed++; return setOf("payment.read") }
    }

    @Test
    fun `a missing permission is the existing 403 COMMON FORBIDDEN, so clients keep reading it`() {
        val e = assertFailsWith<ApplicationException> { access.require(OperatorRef("acc_1"), "payment.refund") }
        assertEquals(HttpStatus.FORBIDDEN, e.status)
        assertEquals("COMMON.FORBIDDEN", e.errorCode.code)
    }

    @Test
    fun `an anonymous caller is 401`() {
        val e = assertFailsWith<ApplicationException> { access.require(null, "payment.read") }
        assertEquals(HttpStatus.UNAUTHORIZED, e.status)
    }

    @Test
    fun `a held permission passes`() {
        access.require(OperatorRef("acc_1"), "payment.read")
    }

    @Test
    fun `inside a request permissions of one account are computed once - outside, every time`() {
        access.permissionsOf(OperatorRef("acc_1")); access.permissionsOf(OperatorRef("acc_1"))
        assertEquals(2, computed)

        computed = 0
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        try {
            access.permissionsOf(OperatorRef("acc_1")); access.permissionsOf(OperatorRef("acc_1"))
            assertEquals(1, computed)
            access.permissionsOf(OperatorRef("acc_2"))
            assertEquals(2, computed)
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    @Test
    fun `a request that ends while computing is computed once - the failed memo write does not compute twice`() {
        val ended = object : org.springframework.web.context.request.RequestAttributes by ServletRequestAttributes(MockHttpServletRequest()) {
            override fun getAttribute(name: String, scope: Int): Any? = null
            override fun setAttribute(name: String, value: Any, scope: Int) = throw IllegalStateException("request completed")
        }
        RequestContextHolder.setRequestAttributes(ended)
        try {
            computed = 0
            access.permissionsOf(OperatorRef("acc_1"))
            assertEquals(1, computed)
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }
}
