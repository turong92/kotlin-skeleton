package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardLabel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import dev.sumin.skeleton.rewardnooptional.RewardNoOptionalApp
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@TestConfiguration(proxyBeanMethods = false)
class StubRewardPorts {
    @Bean fun store(): RewardGrantStore = object : RewardGrantStore {
        override fun hasOpenTransaction() = true
        override fun <T> inTransaction(block: () -> T): T = block()
        override fun insertIfAbsent(grant: NewRewardGrant) = true
        override fun lockPending(id: String): RewardGrantView? = null
        override fun markIssued(id: String, resultRef: String, at: Instant) = Unit
        override fun markFailed(id: String, error: String) = false
        override fun reopen(id: String) = false
        override fun cancel(id: String) = false
        override fun find(id: String): RewardGrantRecord? = null
        override fun page(status: RewardGrantStatus?, before: String?, limit: Int) = emptyList<RewardGrantRecord>()
        override fun deleteByAccount(accountId: String) = 0
    }

    @Bean fun tasks(): RewardTasks = RewardTasks { }
}

/** `notification` 이 클래스패스에 없어도 모듈이 뜨고(지급기가 하나도 없어도), 알림 자리는 아무것도 하지 않는다 */
@SpringBootTest(classes = [RewardNoOptionalApp::class])
@Import(StubRewardPorts::class)
class RewardWithoutNotificationTest {
    @Autowired lateinit var notifier: RewardNotifier
    @Autowired lateinit var grants: RewardGrants
    @Autowired lateinit var worker: RewardWorker

    @Test
    fun `boots without notification and the notifier does nothing`() {
        assertEquals(false, runCatching { Class.forName("dev.sumin.skeleton.notification.NotificationPublisher") }.isSuccess, "notification must be off the classpath")
        notifier.issued(RewardGrantView("g", "a", "point", emptyMap(), "s", "k"), RewardLabel("t", "m"))
        assertNotNull(grants)
        worker.process("missing")   // 지급기 없음 · 건 없음 - 아무 일 없이 끝난다
    }
}
