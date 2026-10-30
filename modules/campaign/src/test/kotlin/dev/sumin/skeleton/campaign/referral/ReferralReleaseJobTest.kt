package dev.sumin.skeleton.campaign.referral

import dev.sumin.skeleton.campaign.CampaignAutoConfiguration
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.FakeCampaignStore
import dev.sumin.skeleton.campaign.RecordingGrants
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.reward.RewardGrants
import dev.sumin.skeleton.scheduler.SkeletonScheduleResolver
import dev.sumin.skeleton.scheduler.SkeletonSchedule
import dev.sumin.skeleton.scheduler.SkeletonScheduled
import dev.sumin.skeleton.scheduler.SkeletonSchedulerProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** `scheduler` 가 클래스패스에 있을 때: 해제 작업이 10분마다, 하나뿐인 락 키로 등록된다 (없을 때는 `noOptionalTest`) */
class ReferralReleaseJobTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CampaignAutoConfiguration::class.java))
        .withBean(CampaignStore::class.java, { FakeCampaignStore() })
        .withBean(RewardGrants::class.java, { RecordingGrants() })

    @Test
    fun `the release job is a bean and its schedule is every 10 minutes under a lock`() {
        runner.run { context ->
            assertNotNull(context.getBean(ReferralReleaseJob::class.java))
            assertEquals(1, context.getBeansOfType(DeployGuard::class.java).size)
        }
        val annotation = ReferralReleaseJob::class.java.getMethod("run").getAnnotation(SkeletonScheduled::class.java)
        assertEquals("campaign.referral.release", annotation.id)
        assertEquals("campaign.referral.release", annotation.lockKey)
        val schedule = SkeletonScheduleResolver(SkeletonSchedulerProperties()).resolve(annotation)
        assertEquals(Duration.ofMinutes(10), (schedule as SkeletonSchedule.FixedDelay).interval)
    }
}
