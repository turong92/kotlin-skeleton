package dev.sumin.skeleton.common

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.core.MethodParameter
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

class GlobalExceptionHandlerTypeMismatchTest {
    @Test
    fun `a query or path value that cannot be converted is a 400 naming the parameter, not a 500`() {
        val ex = MethodArgumentTypeMismatchException("NOPE", Int::class.java, "status", MethodParameter(Any::class.java.getMethod("equals", Any::class.java), 0), NumberFormatException("bad"))

        val response = GlobalExceptionHandler().handleTypeMismatch(ex)

        assertEquals(400, response.statusCode.value())
        val body = requireNotNull(response.body)
        assertEquals("COMMON.PARAMETER_VALIDATION_FAILED", body.code)
        assertEquals("status", body.errors?.single()?.field)
        assertEquals("TypeMismatch", body.errors?.single()?.code)
    }
}
