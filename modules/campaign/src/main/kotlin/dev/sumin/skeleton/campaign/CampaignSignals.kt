package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.reward.RewardGrants

/**
 * 앱이 부르는 자리 - 업적 수치의 **현재 값**을 보고한다(예: 노트 저장 뒤 자기 개수를 세어 `report(owner, "notes.created", 12)`). 서버 API 뿐이다.
 * 호출자 트랜잭션에 합류한다(없으면 자기 트랜잭션).
 */
interface CampaignSignals {
    /**
     * 수치를 `max(기존, [value])` 로 올리고(낮은 값은 무시), 진행 중인 `achievement` 이벤트 중 같은 [metric] 의 **값 이하인 모든 단계** 보상을 지급 건으로 기록한다.
     * 지급 건 키(`<이벤트>:<계정>:achv:<기준값>:<보상 해시>` - 해시는 보상의 종류 · params 로 정하므로 단계 안에서 보상을 끼우거나 순서를 바꿔도 이미 준 보상의 키는 그대로다)가 같은 단계를 한 번으로 접으므로 같은 값을 다시 보고해도 두 번 주지 않고, 이벤트를 나중에 만들어도 다음 보고에서 이미 넘은 단계를 준다.
     * 형식이 틀린 [metric] · 음수 [value] 는 `400 CAMPAIGN.CONFIG_INVALID`.
     */
    fun report(accountId: String, metric: String, value: Long)
}

class AchievementService(
    private val store: CampaignStore,
    private val grants: RewardGrants,
    private val time: TimeProvider,
) : CampaignSignals {
    override fun report(accountId: String, metric: String, value: Long) {
        if (!AchievementConfig.METRIC.matches(metric)) throw CampaignException(CampaignErrorCode.CONFIG_INVALID, "metric must match [a-z0-9._-]{1,64}")
        if (value < 0) throw CampaignException(CampaignErrorCode.CONFIG_INVALID, "value must not be negative")
        store.inTransaction {
            val current = store.upsertProgressMax(accountId, metric, value, time.now())
            val now = time.now()
            store.list().filter { it.kind == AchievementKind.KIND && it.isActive(now) }.forEach { campaign ->
                val config = AchievementConfig.parse(campaign.config)
                if (config.metric != metric) return@forEach
                config.milestones.filter { it.value <= current }.forEach { step ->
                    step.rewards.forEach { spec ->
                        grants.record(accountId, spec.type, spec.params, SOURCE_TYPE, "${campaign.id}:$accountId:achv:${step.value}:${spec.identity()}")
                    }
                }
            }
        }
    }

    companion object {
        const val SOURCE_TYPE = "achievement"
    }
}
