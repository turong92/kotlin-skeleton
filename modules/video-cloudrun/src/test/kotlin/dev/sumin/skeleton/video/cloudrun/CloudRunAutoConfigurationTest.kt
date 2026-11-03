package dev.sumin.skeleton.video.cloudrun

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.video.VideoWaker
import dev.sumin.skeleton.video.VideoWakeRuns
import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.video.VideoWakeException
import dev.sumin.skeleton.video.alert.VideoAlertKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 조립 - 기본 꺼짐이면 아무 빈도 없고, 켜면 상한 · 기록을 두른 깨우기가 선다 */
class CloudRunAutoConfigurationTest {
    private val account = TestServiceAccount()
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CloudRunAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })
        .withBean(VideoWakeRuns::class.java, { InMemoryWakeRuns() })

    private val on = arrayOf(
        "skeleton.video.cloud-run.enabled=true", "skeleton.video.cloud-run.project=demo-project",
        "skeleton.video.cloud-run.region=asia-northeast3", "skeleton.video.cloud-run.job=video-worker",
    )

    @Test
    fun `off by default - no waker and no properties bean`() {
        runner.run { ctx ->
            assertTrue(ctx.getBeansOfType(VideoWaker::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(CloudRunProperties::class.java).isEmpty())
        }
    }

    @Test
    fun `on with credentials - the waker is the guarded cloud run one`() {
        runner.withPropertyValues(*on, "skeleton.video.cloud-run.credentials-json=" + account.json()).run { ctx ->
            val waker = ctx.getBean(VideoWaker::class.java)
            assertTrue(waker is GuardedWaker && waker.enabled)
            assertNotNull(ctx.getBean(CloudRunProperties::class.java))
        }
    }

    @Test
    fun `on without credentials fails startup naming the property, not a value`() {
        runner.withPropertyValues(*on).run { ctx ->
            val failure = ctx.startupFailure
            assertNotNull(failure)
            assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { "credentials-json" in (it.message ?: "") })
        }
    }

    @Test
    fun `with the alert module present, the third failure in a row emits VIDEO_WAKE_FAILED immediately`() {
        val emitted = mutableListOf<Triple<AlertKind, String, Boolean>>()
        val alerts = object : OwnerAlerts {
            override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) { emitted += Triple(kind, detail, immediate) }
        }
        FakeGoogleServer(account).use { google ->
            google.runStatus = 500
            runner.withBean(OwnerAlerts::class.java, { alerts }).withPropertyValues(
                *on, "skeleton.video.cloud-run.credentials-json=" + account.json(), "skeleton.video.cloud-run.api-base=${google.base}",
                "skeleton.video.cloud-run.token-uri=${google.base}/token", "skeleton.video.cloud-run.min-wake-gap=0s",
            ).run { ctx ->
                val waker = ctx.getBean(VideoWaker::class.java)
                repeat(3) { assertFailsWith<VideoWakeException> { waker.wake() } }
                assertEquals(1, emitted.size)
                assertEquals(VideoAlertKind.VIDEO_WAKE_FAILED, emitted.single().first)
                assertTrue(emitted.single().third)
            }
        }
    }
}
