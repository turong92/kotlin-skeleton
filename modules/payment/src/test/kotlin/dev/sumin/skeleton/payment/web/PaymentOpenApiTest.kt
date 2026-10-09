package dev.sumin.skeleton.payment.web

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

/** 명세 기여: 웹훅 수신 경로는 공개 문서에서 숨기고, 사용자 · 운영자 경로의 모든 핸들러는 요약(@Operation)을 단다. */
class PaymentOpenApiTest {
    @Test
    fun `the webhook receiver is hidden from the public document`() {
        assertTrue(PaymentWebhookController::class.java.isAnnotationPresent(Hidden::class.java))
    }

    @Test
    fun `every user and operator handler documents itself`() {
        listOf<KClass<*>>(PaymentController::class, PaymentOrderController::class, PaymentAdminController::class).forEach { type ->
            val handlers = type.java.declaredMethods.filter { it.isAnnotationPresent(GetMapping::class.java) || it.isAnnotationPresent(PostMapping::class.java) }
            assertTrue(handlers.isNotEmpty(), "$type has handlers")
            handlers.forEach { assertTrue(it.isAnnotationPresent(Operation::class.java), "${type.simpleName}.${it.name} needs @Operation") }
        }
    }
}
