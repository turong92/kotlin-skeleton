package dev.sumin.skeleton.payment.web

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 운영자 응답은 사용자 응답의 칸을 **평평하게 되풀이**한다(OpenAPI 문서 생성기 때문에 품지 못한다). 사용자 응답에 칸이 늘 때 운영자 쪽이 조용히 빠지면
 * 운영자 화면만 그 칸을 못 보므로, 운영자 생성자의 앞부분이 사용자 생성자와 이름 · 종류 · 순서까지 같다는 것을 고정한다(뒤쪽은 운영자만의 칸).
 */
class OperatorResponseDriftTest {
    private fun fields(type: KClass<*>) = checkNotNull(type.primaryConstructor).parameters.map { it.name to it.type.toString() }

    private fun assertPrefix(user: KClass<*>, admin: KClass<*>) {
        val userFields = fields(user)
        val adminFields = fields(admin)
        assertEquals(
            userFields,
            adminFields.take(userFields.size),
            "${admin.simpleName} must start with every field of ${user.simpleName} (same name, type and order) - then its own fields. " +
                "A field added to ${user.simpleName} but not here would be invisible to operators.",
        )
    }

    @Test
    fun `the operator order starts with every field of the user order`() = assertPrefix(OrderResponse::class, AdminOrderResponse::class)

    @Test
    fun `the operator refund starts with every field of the user refund`() = assertPrefix(RefundResponse::class, AdminRefundResponse::class)
}
