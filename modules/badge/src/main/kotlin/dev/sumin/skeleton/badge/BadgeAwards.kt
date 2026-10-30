package dev.sumin.skeleton.badge

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider

/**
 * 수여 · 회수 — 앱 API([Badges]) · 운영자 · 보상 지급기가 모두 여기를 지난다. 열린 트랜잭션에 합류하므로 지급기가 일꾼의 트랜잭션 안에서 불러도 된다.
 *
 * 멱등: (배지, 계정)당 보유 줄 하나(`unique`)가 같은 배지를 두 번 주지 않고, `(AWARDED, 출처, 출처 키)` 내역이 같은 원인의 재시도를 접는다 —
 * 회수된 뒤라도 같은 원인(같은 지급 건 · 같은 [Badges.award] 의 `sourceRef`)으로는 다시 주지 않는다.
 * 앱 호출의 출처 키는 `계정:코드:sourceRef` 로 저장한다(내역의 유니크가 계정 · 배지를 가로질러 겹치지 않게).
 */
class BadgeAwards(
    private val store: BadgeStore,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
    /** 운영자 수여 때 계정 존재를 확인한다. 구현이 없으면 null - 확인 없이 준다 */
    private val accountFacts: () -> AccountFacts? = { null },
    private val notifier: () -> BadgeNotifier,
) : Badges {
    override fun award(accountId: String, code: String, sourceRef: String): BadgeHolding {
        if (sourceRef.isBlank() || sourceRef.length > MAX_REF) throw BadgeException(BadgeErrorCode.CONFIG_INVALID, "sourceRef is required (at most $MAX_REF characters)")
        return awardOne(accountId, byCode(code), BadgeSource.APP, "$accountId:$code:$sourceRef", null, null)
    }

    /** 보상 지급기가 부른다 - 출처 키는 지급 건 id. 알림은 `reward` 가 보낸다 */
    fun awardForGrant(accountId: String, code: String, grantId: String): BadgeHolding =
        awardOne(accountId, byCode(code), BadgeSource.GRANT, grantId, null, null)

    /** 운영자 수동 수여 - 계정마다 한 번씩(중복 입력은 접는다), 이미 가진 계정은 `alreadyHeld`, 없는 계정은 수여하지 않고 `unknownAccountIds` */
    fun awardByOperator(badgeId: String, accountIds: List<String>, reason: String?, actorId: String): AdminAwardResult = store.inTransaction {
        // 정렬해서 잠근다 - 입력 순서가 [A,B] 와 [B,A] 인 두 요청이 서로의 줄을 기다리며 교착하지 않게
        val unique = accountIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (unique.isEmpty()) throw BadgeException(BadgeErrorCode.CONFIG_INVALID, "accountIds is required")
        if (unique.size > MAX_ADMIN_ACCOUNTS) throw BadgeException(BadgeErrorCode.CONFIG_INVALID, "at most $MAX_ADMIN_ACCOUNTS accounts per request")
        val why = reason?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > MAX_REASON) throw reasonTooLong() }
        val definition = store.findDefinition(badgeId) ?: throw BadgeException(BadgeErrorCode.NOT_FOUND, "badge $badgeId not found")
        if (definition.status == BadgeStatus.ARCHIVED) throw BadgeException(BadgeErrorCode.ARCHIVED, "badge '${definition.code}' is archived")
        val awarded = ArrayList<String>()
        val held = ArrayList<String>()
        val unknown = ArrayList<String>()
        val facts = accountFacts()
        for (accountId in unique) {
            if (facts != null && facts.createdAt(accountId) == null) {
                unknown += accountId
                continue
            }
            val result = awardOne(accountId, definition, BadgeSource.ADMIN, null, actorId, why)
            (if (result.newlyAwarded) awarded else held) += accountId
        }
        AdminAwardResult(awarded, held, unknown)
    }

    override fun revoke(accountId: String, code: String, reason: String): Boolean = store.inTransaction {
        val why = requireReason(reason)
        val definition = byCode(code)
        val award = store.lockAward(definition.id, accountId)
        award != null && revokeOne(award, BadgeSource.APP, null, why)
    }

    /** 운영자 회수 - 사유 필수. 없거나 이미 회수된 줄은 404 */
    fun revokeByOperator(awardId: String, reason: String?, actorId: String) = store.inTransaction {
        val why = requireReason(reason)
        val award = store.lockAwardById(awardId)?.takeIf { it.status == BadgeAwardStatus.ACTIVE }
            ?: throw BadgeException(BadgeErrorCode.NOT_FOUND, "no active award $awardId")
        revokeOne(award, BadgeSource.ADMIN, actorId, why)
    }

    override fun holdingsOf(accountIds: Collection<String>): Map<String, List<PublicBadge>> {
        val ids = accountIds.toSet()
        val found = store.publicBadgesOf(ids)
        return ids.associateWith { found[it].orEmpty() }
    }

    /** 수여 · 회수 내역, 최신순 커서 */
    fun history(badgeId: String?, accountId: String?, cursor: String?, size: Int): AwardHistoryPage {
        val limit = size.coerceIn(1, MAX_PAGE)
        val rows = store.pageEvents(badgeId, accountId, cursor?.takeIf { it.isNotBlank() }, limit + 1)
        val page = rows.take(limit)
        val badges = store.findDefinitions(page.map { it.badgeId }.toSet())
        return AwardHistoryPage(page.map { AwardHistoryRow(it, badges[it.badgeId]) }, if (rows.size > limit) page.last().id else null)
    }

    private fun byCode(code: String) = store.findDefinitionByCode(code) ?: throw BadgeException(BadgeErrorCode.NOT_FOUND, "badge '$code' not found")

    private fun awardOne(accountId: String, definition: BadgeDefinition, source: BadgeSource, sourceRef: String?, actorId: String?, reason: String?): BadgeHolding = store.inTransaction {
        if (definition.status == BadgeStatus.ARCHIVED) throw BadgeException(BadgeErrorCode.ARCHIVED, "badge '${definition.code}' is archived")
        val now = time.now()
        // 같은 원인으로 이미 처리했다면(일꾼 재시도 · 앱이 같은 sourceRef 로 다시) 지금 상태 그대로 돌려준다 - 회수된 뒤에도 다시 주지 않는다
        val processed = sourceRef != null && store.hasEvent(BadgeAction.AWARDED, source, sourceRef)
        // 줄이 없을 때는 잠금 읽기보다 넣기를 먼저 한다 - 없는 키를 잠그면(MySQL 틈 잠금) 동시에 온 두 호출이 서로 넣기를 막아 교착한다
        var created = false
        if (!processed) {
            val fresh = BadgeAward(ids.newId("bda_"), definition.id, accountId, BadgeAwardStatus.ACTIVE, true, now, null)
            store.insertAwardIfAbsent(fresh)
            val held = store.lockAward(definition.id, accountId)   // 동시에 온 쪽이 이겼다면 그 줄(현재 값)
            created = when {
                held == null -> false
                held.id == fresh.id -> true
                held.status == BadgeAwardStatus.REVOKED -> store.reactivateAward(held.id, now)   // 조건부 갱신 - 동시에 와도 한쪽만 이긴다
                else -> false
            }
        }
        val current = store.lockAward(definition.id, accountId) ?: throw BadgeException(BadgeErrorCode.NOT_FOUND, "badge award for '${definition.code}' not found")
        if (created) {
            val eventId = ids.newId("bde_")
            store.insertEventIfAbsent(BadgeAwardEvent(eventId, current.id, definition.id, accountId, BadgeAction.AWARDED, source, sourceRef, actorId, reason, now))
            if (source != BadgeSource.GRANT) AfterCommit.run { notifier().awarded(accountId, definition, source, eventId) }
        }
        BadgeHolding(current.id, definition.code, current.awardedAt, created, current.status == BadgeAwardStatus.ACTIVE)
    }

    private fun revokeOne(award: BadgeAward, source: BadgeSource, actorId: String?, reason: String): Boolean {
        val now = time.now()
        if (!store.revokeAward(award.id, now)) return false
        store.insertEventIfAbsent(BadgeAwardEvent(ids.newId("bde_"), award.id, award.badgeId, award.accountId, BadgeAction.REVOKED, source, null, actorId, reason, now))
        return true
    }

    private fun requireReason(reason: String?): String {
        val why = reason?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadgeException(BadgeErrorCode.CONFIG_INVALID, "reason is required")
        if (why.length > MAX_REASON) throw reasonTooLong()
        return why
    }

    private fun reasonTooLong() = BadgeException(BadgeErrorCode.CONFIG_INVALID, "reason is at most $MAX_REASON characters")

    companion object {
        const val MAX_REF = 60
        const val MAX_REASON = 200
        const val MAX_ADMIN_ACCOUNTS = 100
        const val MAX_PAGE = 200
    }
}
