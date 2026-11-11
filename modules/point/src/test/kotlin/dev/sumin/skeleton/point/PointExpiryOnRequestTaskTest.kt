package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.pointtest.FakeWallet
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 인스턴스 0대 배포에서는 03:30 타이머가 돌지 않는다 - 깨우기가 부르는 [OnRequestTask] 로도 소멸이 돈다 */
class PointExpiryOnRequestTaskTest {
    private val calls = CopyOnWriteArrayList<Instant>()
    private val wallet = object : PointWallet by FakeWallet() {
        override fun expireDue(now: Instant): Int = 0.also { calls += now }
    }
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PointAutoConfiguration::class.java))
        .withBean(PointWallet::class.java, { wallet })
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    @Test
    fun `the wake hook closes expired lots through the wallet`() {
        runner.run { context ->
            val task = context.getBeansOfType(OnRequestTask::class.java).values.single { it.name == "point-expire" }
            task.run()
            task.run() // 멱등 - 두 번 불려도 해가 없다
            assertEquals(2, calls.size)
        }
    }
}
