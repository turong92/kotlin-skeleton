package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class InquiryRetentionTest {
    private val now = Instant.parse("2027-11-02T00:00:00Z")
    private val repository = InMemoryInquiryRepository()
    private val retention = InquiryRetention(repository, Duration.ofDays(30), TimeProvider { now })

    private fun inquiry() =
        repository.create(NewInquiry(InquiryKind.QUESTION, "보관 기한 시험 문의입니다", "a@b.co", "acc_1", null, "ko", now.minus(Duration.ofDays(400))))

    private fun handled(handledAt: Instant) = inquiry().also { repository.setStatus(it.id, InquiryStatus.DONE, handledAt) }

    @Test
    fun `deletes handled inquiries past the period and keeps unhandled, reopened and recent ones`() {
        val old = handled(now.minus(Duration.ofDays(31)))
        val recent = handled(now.minus(Duration.ofDays(29)))
        val unhandled = inquiry()   // 만든 지는 오래됐지만 처리 전이다
        val reopened = handled(now.minus(Duration.ofDays(31))).also { repository.setStatus(it.id, InquiryStatus.NEW, now) }

        assertEquals(1, retention.purge())

        assertNull(repository.find(old.id))
        assertNotNull(repository.find(recent.id))
        assertNotNull(repository.find(unhandled.id))
        assertNotNull(repository.find(reopened.id))
        assertEquals(0, retention.purge())   // 겹쳐 돌아도 같다
    }

    @Test
    fun `a retention of zero or less fails startup, unset means off`() {
        assertFailsWith<IllegalArgumentException> { InquiryProperties(retention = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { InquiryProperties(retention = Duration.ofDays(-1)) }
        assertNull(InquiryProperties().retention)
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InquiryAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    @Test
    fun `the sweep and its wake task exist only when a retention is configured`() {
        runner.run { ctx ->
            assertEquals(0, ctx.getBeansOfType(InquiryRetention::class.java).size)
            assertEquals(0, ctx.getBeansOfType(OnRequestTask::class.java).size)
        }
        runner.withPropertyValues("skeleton.inquiry.retention=").run { ctx ->   // 문서의 설정 예시처럼 값을 비운 모양 = 꺼짐
            assertEquals(null, ctx.startupFailure)
            assertEquals(0, ctx.getBeansOfType(InquiryRetention::class.java).size)
        }
        runner.withPropertyValues("skeleton.inquiry.retention=365d", "skeleton.job-queue.mode=on-request").run { ctx ->
            assertEquals(1, ctx.getBeansOfType(InquiryRetention::class.java).size)
            assertEquals(listOf("inquiry-retention"), ctx.getBeansOfType(OnRequestTask::class.java).values.map { it.name })
        }
    }

    @Test
    fun `retention without anything to run it fails startup instead of silently never deleting`() {
        runner.withPropertyValues("skeleton.inquiry.retention=365d").run { ctx ->
            val message = generateSequence(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue(message.contains("scheduler") && message.contains("skeleton.inquiry.retention"))
        }
    }
}
