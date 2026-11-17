package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.adswebtest.FakeWebRewardStore
import dev.sumin.skeleton.adswebtest.MutableClock
import dev.sumin.skeleton.adswebtest.RecordingIssuer
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

/** 스위치 · 기동 가드 — 꺼짐이면 빈이 없고(= 404), 보상 종류를 정했는데 지급기가 없거나 params 가 틀리면 기동이 실패한다 */
class WebRewardedSwitchTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebRewardedAutoConfiguration::class.java, WebRewardedErasureAutoConfiguration::class.java))
        .withBean(FakeWebRewardStore::class.java)
        .withBean(TimeProvider::class.java, { MutableClock() })

    @Test
    fun `off by default - no controller bean, so both paths are 404`() {
        runner.run { assertTrue(it.getBeansOfType(WebRewardedController::class.java).isEmpty()) }
        runner.withPropertyValues("skeleton.ads-web-rewarded.enabled=true").run {
            assertNotNull(it.getBean(WebRewardedController::class.java))
            assertNotNull(it.getBean(WebRewardTokens::class.java))
        }
    }

    @Test
    fun `account erasure is registered whenever a store exists - even with the module off or outside a web app`() {
        runner.run { assertNotNull(it.getBean("webRewardErasure")) }
        org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebRewardedErasureAutoConfiguration::class.java))
            .withBean(FakeWebRewardStore::class.java)
            .run { assertNotNull(it.getBean("webRewardErasure")) }
    }

    @Test
    fun `without a store bean nothing is registered even when enabled`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebRewardedAutoConfiguration::class.java))
            .withPropertyValues("skeleton.ads-web-rewarded.enabled=true")
            .run { assertTrue(it.getBeansOfType(WebRewardedController::class.java).isEmpty()) }
    }

    @Test
    fun `a reward type with no issuer of that type fails startup and names the setting`() {
        runner.withPropertyValues("skeleton.ads-web-rewarded.enabled=true", "skeleton.ads-web-rewarded.reward-type=point").run {
            val message = generateSequence(it.startupFailure) { e -> e.cause }.joinToString(" | ") { e -> e.message.orEmpty() }
            assertTrue("skeleton.ads-web-rewarded.reward-type" in message, message)
        }
    }

    @Test
    fun `reward params the issuer refuses fail startup`() {
        runner.withBean(RewardIssuer::class.java, { RecordingIssuer() })
            .withPropertyValues("skeleton.ads-web-rewarded.enabled=true", "skeleton.ads-web-rewarded.reward-type=point")
            .run { assertNotNull(it.startupFailure) }
        runner.withBean(RewardIssuer::class.java, { RecordingIssuer() })
            .withPropertyValues("skeleton.ads-web-rewarded.enabled=true", "skeleton.ads-web-rewarded.reward-type=point", "skeleton.ads-web-rewarded.reward-params.amount=1")
            .run { assertEquals(null, it.startupFailure) }
    }
}
