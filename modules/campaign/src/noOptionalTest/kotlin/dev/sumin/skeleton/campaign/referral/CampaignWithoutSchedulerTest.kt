package dev.sumin.skeleton.campaign.referral

import dev.sumin.skeleton.campaign.CampaignAutoConfiguration
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.Referrals
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.reward.RewardGrants
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** `scheduler` 가 클래스패스에 정말 없을 때(소비자 시점) - 초대는 뜨고 해제 작업만 없다. 앱이 `Referrals.releaseDue()` 를 부른다 */
class CampaignWithoutSchedulerTest {
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> error("${method.name} is not used by this test") } as T

    @Test
    fun `the scheduler is really absent here`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.scheduler.SkeletonScheduled") }
    }

    @Test
    fun `referrals boot with a guard but without the release job`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CampaignAutoConfiguration::class.java))
            .withBean(CampaignStore::class.java, { unused<CampaignStore>() })
            .withBean(RewardGrants::class.java, { unused<RewardGrants>() })
            .run { context ->
                assertNotNull(context.getBean(Referrals::class.java))
                assertEquals(1, context.getBeansOfType(DeployGuard::class.java).size)
                assertEquals(0, context.getBeansOfType(ReferralReleaseJob::class.java).size)
            }
    }
}
