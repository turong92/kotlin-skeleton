package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.pointtest.FakeWallet
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 잡아 두기를 켜면 버려진 건을 풀 길이 있어야 한다 - 없으면 포인트가 조용히 묶이므로 기동 실패 */
class PointReservationSweepWiringTest {
    private val sweeps = CopyOnWriteArrayList<Instant>()
    private val wallet = object : PointWallet by FakeWallet() {
        override fun expireReservations(now: Instant): Int = 0.also { sweeps += now }
    }
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PointAutoConfiguration::class.java))
        .withBean(PointWallet::class.java, { wallet })
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    @Test
    fun `reservations are off by default - no sweep and no demand on the scheduler`() {
        runner.run { context ->
            assertEquals(0, context.getBeansOfType(PointReservationSweep::class.java).size)
            assertTrue(context.getBeansOfType(OnRequestTask::class.java).values.none { it.name == "point-reservation-sweep" })
        }
    }

    @Test
    fun `switching reservations on without anything to run the sweep fails the start`() {
        runner.withPropertyValues("skeleton.point.reservations.enabled=true").run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("skeleton.point.reservations") })
        }
    }

    @Test
    fun `in the request-driven mode the wake call runs the sweep`() {
        runner.withPropertyValues("skeleton.point.reservations.enabled=true", "skeleton.job-queue.mode=on-request").run { context ->
            val task = context.getBeansOfType(OnRequestTask::class.java).values.single { it.name == "point-reservation-sweep" }
            task.run()
            task.run()   // 멱등 - 두 번 불려도 해가 없다
            assertEquals(2, sweeps.size)
        }
    }

    @Test
    fun `a non-positive hold timeout is refused`() {
        runner.withPropertyValues("skeleton.point.reservations.enabled=true", "skeleton.point.reservations.hold-timeout=0s", "skeleton.job-queue.mode=on-request").run { context ->
            assertNotNull(context.startupFailure)
        }
    }
}
