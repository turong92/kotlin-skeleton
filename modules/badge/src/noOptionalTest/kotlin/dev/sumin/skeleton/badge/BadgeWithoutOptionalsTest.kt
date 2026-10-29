package dev.sumin.skeleton.badge

import dev.sumin.skeleton.badgenooptional.BadgeNoOptionalApp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@TestConfiguration(proxyBeanMethods = false)
class StubBadgePorts {
    @Bean fun store(): BadgeStore = object : BadgeStore {
        private val definition = BadgeDefinition("bdg_1", "first-note", "First note", "", null, false, 0, BadgeStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH)
        private var award: BadgeAward? = null
        override fun <T> inTransaction(block: () -> T): T = block()
        override fun insertDefinition(definition: BadgeDefinition) = true
        override fun findDefinition(id: String) = definition
        override fun findDefinitionByCode(code: String) = definition.takeIf { it.code == code }
        override fun findDefinitions(ids: Collection<String>) = mapOf(definition.id to definition)
        override fun listDefinitions() = listOf(definition)
        override fun holderCounts() = emptyMap<String, Int>()
        override fun updateDefinition(definition: BadgeDefinition) = true
        override fun updateDefinitionStatus(id: String, status: BadgeStatus, at: Instant) = true
        override fun lockAward(badgeId: String, accountId: String) = award
        override fun lockAwardById(id: String) = award
        override fun insertAwardIfAbsent(award: BadgeAward): Boolean { this.award = award; return true }
        override fun reactivateAward(id: String, at: Instant) = false
        override fun revokeAward(id: String, at: Instant) = false
        override fun setVisible(id: String, accountId: String, visible: Boolean) = false
        override fun activeAwardsOf(accountId: String) = listOfNotNull(award)
        override fun publicBadgesOf(accountIds: Collection<String>) = emptyMap<String, List<PublicBadge>>()
        override fun hasEvent(action: BadgeAction, source: BadgeSource, sourceRef: String) = false
        override fun insertEventIfAbsent(event: BadgeAwardEvent) = true
        override fun pageEvents(badgeId: String?, accountId: String?, before: String?, limit: Int) = emptyList<BadgeAwardEvent>()
        override fun eraseAccount(accountId: String) = 0
    }
}

/** `notification` 이 클래스패스에 없어도(`reward` · `coupon` · `point` 도 없다) 모듈이 뜨고 수여가 되며, 알림 자리는 아무것도 하지 않는다 */
@SpringBootTest(classes = [BadgeNoOptionalApp::class])
@Import(StubBadgePorts::class)
class BadgeWithoutOptionalsTest {
    @Autowired lateinit var badges: Badges
    @Autowired lateinit var notifier: BadgeNotifier

    @Test
    fun `boots and awards without notification or any other reward module`() {
        assertEquals(false, runCatching { Class.forName("dev.sumin.skeleton.notification.NotificationPublisher") }.isSuccess, "notification must be off the classpath")
        assertEquals(false, runCatching { Class.forName("dev.sumin.skeleton.reward.RewardGrants") }.isSuccess, "reward must be off the classpath")
        assertNotNull(notifier)
        assertEquals(true, badges.award("acc_1", "first-note", "r1").newlyAwarded)
    }
}
