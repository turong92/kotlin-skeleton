package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

/** 운영자가 넣는 이벤트 한 건의 입력 */
data class CampaignInput(val name: String, val startsAt: Instant, val endsAt: Instant?, val config: Map<String, Any?>)

/**
 * 이벤트의 만들기 · 고치기 · 켜고 끄기(운영) 와 진행 중 목록(사용자). 저장 전에 [CampaignKind] 가 설정을 검증한다 -
 * 모르는 종류 · 모르는 보상 타입 · 지급기가 거절하는 params 는 `400 CAMPAIGN.CONFIG_INVALID`.
 */
class Campaigns(
    private val store: CampaignStore,
    private val kinds: () -> List<CampaignKind>,
    private val issuers: () -> List<RewardIssuer>,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
) {
    fun create(kind: String, input: CampaignInput): Campaign {
        validate(kind, input)
        val now = time.now()
        val campaign = Campaign(ids.newId(ID_PREFIX), kind, input.name.trim(), true, input.startsAt, input.endsAt, input.config, now, now)
        store.inTransaction { store.insert(campaign) }
        return campaign
    }

    /** 종류는 못 바꾼다 */
    fun update(id: String, input: CampaignInput): Campaign = store.inTransaction {
        val existing = get(id)
        validate(existing.kind, input)
        val updated = existing.copy(name = input.name.trim(), startsAt = input.startsAt, endsAt = input.endsAt, config = input.config, updatedAt = time.now())
        store.update(updated)
        updated
    }

    fun setEnabled(id: String, enabled: Boolean): Campaign = store.inTransaction {
        val existing = get(id)
        store.setEnabled(id, enabled, time.now())
        existing.copy(enabled = enabled)
    }

    fun get(id: String): Campaign = store.find(id) ?: throw CampaignException(CampaignErrorCode.NOT_FOUND, "campaign $id not found")

    fun list(): List<Campaign> = store.list()

    /** 켜져 있고 기간 안인 이벤트, 먼저 시작한 것부터 */
    fun active(): List<Campaign> {
        val now = time.now()
        return store.list().filter { it.isActive(now) }.sortedBy { it.startsAt }
    }

    private fun validate(kind: String, input: CampaignInput) {
        val handler = kinds().firstOrNull { it.kind == kind } ?: throw CampaignException(CampaignErrorCode.CONFIG_INVALID, "unknown campaign kind '$kind'")
        if (input.name.isBlank() || input.name.trim().length > MAX_NAME) throw CampaignException(CampaignErrorCode.CONFIG_INVALID, "name must be 1..$MAX_NAME characters")
        if (input.endsAt != null && !input.endsAt.isAfter(input.startsAt)) throw CampaignException(CampaignErrorCode.CONFIG_INVALID, "endsAt must be after startsAt")
        handler.validate(input.config, issuers().associateBy { it.type })
    }

    companion object {
        const val ID_PREFIX = "cmp_"
        private const val MAX_NAME = 100
    }
}
