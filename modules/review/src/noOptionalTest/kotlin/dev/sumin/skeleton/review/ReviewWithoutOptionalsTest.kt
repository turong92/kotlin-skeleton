package dev.sumin.skeleton.review

import dev.sumin.skeleton.review.web.ReviewController
import dev.sumin.skeleton.reviewnooptional.ReviewNoOptionalApp
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@TestConfiguration(proxyBeanMethods = false)
class StubReviewStore {
    /** 저장소 포트의 빈 구현 - 아무것도 저장하지 않고 쓰기 길이 끝까지 돌 만큼만 답한다 */
    @Bean fun store(): ReviewStore = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ReviewStore::class.java)) { _, method, args ->
        when (method.name) {
            "inTransaction" -> @Suppress("UNCHECKED_CAST") (args[0] as () -> Any?).invoke()
            "insertIfAbsent" -> true
            "hasLivePurchase" -> false
            "stats" -> TargetStats.EMPTY
            else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else if (method.returnType == Int::class.javaPrimitiveType) 0 else null
        }
    } as ReviewStore
}

/** `notification` · `idempotency` 가 클래스패스에 없어도 모듈이 뜨고 쓰기 길이 돈다 - 선택 통합은 있을 때만 켜진다 */
@SpringBootTest(classes = [ReviewNoOptionalApp::class], properties = ["skeleton.review.target-types=product"])
@Import(StubReviewStore::class)
class ReviewWithoutOptionalsTest {
    @Autowired lateinit var service: ReviewService
    @Autowired lateinit var controller: ReviewController

    @Test
    fun `boots and writes without notification or idempotency on the classpath`() {
        assertFalse(runCatching { Class.forName("dev.sumin.skeleton.notification.NotificationPublisher") }.isSuccess, "notification must be off the classpath")
        assertFalse(runCatching { Class.forName("dev.sumin.skeleton.idempotency.IdempotentOperation") }.isSuccess, "idempotency must be off the classpath")
        assertNotNull(controller)
        assertEquals(ReviewStatus.PUBLISHED, service.create("acc_1", ReviewTarget("product", "basic"), ReviewInput(5, "fine")).status)
    }
}
