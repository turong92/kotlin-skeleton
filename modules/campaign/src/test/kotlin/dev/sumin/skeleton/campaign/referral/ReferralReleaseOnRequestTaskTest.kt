package dev.sumin.skeleton.campaign.referral

import dev.sumin.skeleton.campaign.CampaignAutoConfiguration
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.FakeCampaignStore
import dev.sumin.skeleton.campaign.RecordingGrants
import dev.sumin.skeleton.campaign.Referrals
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.reward.RewardGrants
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 인스턴스 0대 배포에서는 10분 타이머가 돌지 않는다 - 깨우기가 부르는 [OnRequestTask] 가 보류 기한이 지난 초대를 푼다 */
class ReferralReleaseOnRequestTaskTest {
    private val released = AtomicInteger()
    private val referrals = object : Referrals {
        override fun qualify(inviteeId: String, event: String?) = false
        override fun cancel(inviteeId: String, reason: String) = false
        override fun cancelPending(inviteeId: String, reason: String) = false
        override fun releaseDue(): Int = released.incrementAndGet()
    }
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CampaignAutoConfiguration::class.java))
        .withBean(CampaignStore::class.java, { FakeCampaignStore() })
        .withBean(RewardGrants::class.java, { RecordingGrants() })
        .withBean(Referrals::class.java, { referrals })

    @Test
    fun `the wake hook releases due referrals through Referrals`() {
        runner.run { context ->
            val task = context.getBeansOfType(OnRequestTask::class.java).values.single { it.name == "campaign-referral-release" }
            task.run()
            assertEquals(1, released.get())
        }
    }
}
