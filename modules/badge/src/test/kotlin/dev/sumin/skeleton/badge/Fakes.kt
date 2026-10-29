package dev.sumin.skeleton.badge

import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

class MutableTime(var current: Instant = Instant.parse("2026-10-29T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = current
}

/** 메모리 저장소 대역 - 트랜잭션은 모니터 하나라 동시 호출이 줄을 선다(되돌리기는 없다). 조건부 갱신의 뜻은 JDBC 구현과 같게 쓴다 */
class FakeBadgeStore : BadgeStore {
    val definitions = LinkedHashMap<String, BadgeDefinition>()
    val awards = LinkedHashMap<String, BadgeAward>()
    val events = ArrayList<BadgeAwardEvent>()

    fun clear() {
        definitions.clear()
        awards.clear()
        events.clear()
    }

    override fun <T> inTransaction(block: () -> T): T = synchronized(this) { block() }

    override fun insertDefinition(definition: BadgeDefinition): Boolean {
        if (definitions.values.any { it.code == definition.code }) return false
        definitions[definition.id] = definition
        return true
    }

    override fun findDefinition(id: String) = definitions[id]
    override fun findDefinitionByCode(code: String) = definitions.values.firstOrNull { it.code == code }
    override fun findDefinitions(ids: Collection<String>) = definitions.filterKeys { it in ids }
    override fun listDefinitions() = definitions.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun holderCounts() = awards.values.filter { it.status == BadgeAwardStatus.ACTIVE }.groupingBy { it.badgeId }.eachCount()

    override fun updateDefinition(definition: BadgeDefinition): Boolean {
        val row = definitions[definition.id] ?: return false
        definitions[row.id] = row.copy(name = definition.name, description = definition.description, imageUrl = definition.imageUrl, hidden = definition.hidden, sortOrder = definition.sortOrder, updatedAt = definition.updatedAt)
        return true
    }

    override fun updateDefinitionStatus(id: String, status: BadgeStatus, at: Instant): Boolean {
        val row = definitions[id] ?: return false
        definitions[id] = row.copy(status = status, updatedAt = at)
        return true
    }

    override fun lockAward(badgeId: String, accountId: String) = awards.values.firstOrNull { it.badgeId == badgeId && it.accountId == accountId }
    override fun lockAwardById(id: String) = awards[id]

    override fun insertAwardIfAbsent(award: BadgeAward): Boolean {
        if (lockAward(award.badgeId, award.accountId) != null) return false
        awards[award.id] = award
        return true
    }

    override fun reactivateAward(id: String, at: Instant): Boolean {
        val row = awards[id]?.takeIf { it.status == BadgeAwardStatus.REVOKED } ?: return false
        awards[id] = row.copy(status = BadgeAwardStatus.ACTIVE, awardedAt = at, revokedAt = null)
        return true
    }

    override fun revokeAward(id: String, at: Instant): Boolean {
        val row = awards[id]?.takeIf { it.status == BadgeAwardStatus.ACTIVE } ?: return false
        awards[id] = row.copy(status = BadgeAwardStatus.REVOKED, revokedAt = at)
        return true
    }

    override fun setVisible(id: String, accountId: String, visible: Boolean): Boolean {
        val row = awards[id]?.takeIf { it.accountId == accountId && it.status == BadgeAwardStatus.ACTIVE } ?: return false
        awards[id] = row.copy(visible = visible)
        return true
    }

    override fun activeAwardsOf(accountId: String) =
        awards.values.filter { it.accountId == accountId && it.status == BadgeAwardStatus.ACTIVE }
            .sortedWith(compareBy({ definitions[it.badgeId]?.sortOrder ?: 0 }, { it.badgeId }))

    override fun publicBadgesOf(accountIds: Collection<String>): Map<String, List<PublicBadge>> =
        accountIds.associateWith { account ->
            activeAwardsOf(account).filter { it.visible }.mapNotNull { definitions[it.badgeId] }.map { PublicBadge(it.code, it.name, it.description, it.imageUrl) }
        }.filterValues { it.isNotEmpty() }

    override fun hasEvent(action: BadgeAction, source: BadgeSource, sourceRef: String) =
        events.any { it.action == action && it.source == source && it.sourceRef == sourceRef }

    override fun insertEventIfAbsent(event: BadgeAwardEvent): Boolean {
        if (event.sourceRef != null && hasEvent(event.action, event.source, event.sourceRef)) return false
        events += event
        return true
    }

    override fun pageEvents(badgeId: String?, accountId: String?, before: String?, limit: Int) =
        events.filter { (badgeId == null || it.badgeId == badgeId) && (accountId == null || it.accountId == accountId) && (before == null || it.id < before) }
            .sortedByDescending { it.id }.take(limit)

    override fun eraseAccount(accountId: String): Int {
        val a = awards.values.filter { it.accountId == accountId }.map { it.id }
        a.forEach(awards::remove)
        val e = events.removeAll { it.accountId == accountId }
        return a.size + if (e) 1 else 0
    }
}

class RecordingNotifier : BadgeNotifier {
    val sent = ArrayList<Triple<String, String, BadgeSource>>()
    override fun awarded(accountId: String, badge: BadgeDefinition, source: BadgeSource, eventId: String) {
        sent += Triple(accountId, badge.code, source)
    }
}

fun grant(id: String = "rgr_1", accountId: String = "acc_1", code: String = "first-note") =
    RewardGrantView(id, accountId, "badge", mapOf("code" to code), "CAMPAIGN", "k_$id")
