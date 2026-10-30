package dev.sumin.skeleton.review

import dev.sumin.skeleton.review.storage.ReviewStorageAutoConfiguration
import dev.sumin.skeleton.review.storage.StorageReviewPolicy
import dev.sumin.skeleton.storage.StorageKeyOwnership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 사진을 붙일 수 있는 길은 둘뿐이다 - `storage` 의 키 소유 확인이 서 있거나, 앱이 정책을 직접 둔다. 아무것도 없으면 기본 정책이 거절한다 */
class StorageReviewPolicyTest {
    @Configuration(proxyBeanMethods = false)
    class Ownership {
        @Bean fun ownership(): StorageKeyOwnership = StorageKeyOwnership { account, key -> key.startsWith("mine-of-$account/") }
    }

    @Configuration(proxyBeanMethods = false)
    class AppPolicy {
        @Bean fun appPolicy(): ReviewPolicy = object : ReviewPolicy { override fun canAttach(accountId: String, keys: List<String>) = true }
    }

    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ReviewStorageAutoConfiguration::class.java))

    @Test
    fun `an ownership check of storage narrows attachments to the caller's own keys`() {
        runner.withUserConfiguration(Ownership::class.java).run { ctx ->
            val policy = ctx.getBean(ReviewPolicy::class.java)
            assertTrue(policy is StorageReviewPolicy)
            assertTrue(policy.canAttach("acc_1", listOf("mine-of-acc_1/a.jpg")))
            assertFalse(policy.canAttach("acc_1", listOf("mine-of-acc_1/a.jpg", "mine-of-acc_2/b.jpg")), "one foreign key refuses the whole list")
            assertFalse(policy.canAttach("acc_1", listOf("mine-of-acc_2/b.jpg")))
        }
    }

    @Test
    fun `without an ownership check no storage policy stands, and the default refuses any key`() {
        runner.run { ctx -> assertEquals(0, ctx.getBeanNamesForType(ReviewPolicy::class.java).size) }
        assertFalse((object : ReviewPolicy {}).canAttach("acc_1", listOf("any/key.jpg")))
    }

    @Test
    fun `an app policy wins over the storage one`() {
        runner.withUserConfiguration(Ownership::class.java, AppPolicy::class.java).run { ctx ->
            assertEquals(1, ctx.getBeanNamesForType(ReviewPolicy::class.java).size)
            assertTrue(ctx.getBean(ReviewPolicy::class.java).canAttach("acc_1", listOf("whatever")))
        }
    }
}
