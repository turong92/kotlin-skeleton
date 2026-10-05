package dev.sumin.skeleton.scheduler

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/** 모듈만 얹고 설정이 없으면 뜬다 — 외부 인프라 없이 단일 인스턴스용 Noop 락으로 돈다. */
class SchedulerBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SkeletonSchedulerAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(SkeletonScheduledLockManager::class.java)).isSameAs(NoopSkeletonScheduledLockManager)
            }
    }

    @Test
    fun `coexists with other TaskScheduler beans such as the websocket module's`() {
        // notification-websocket 은 TaskScheduler 빈을 둘 만든다 (heartbeat · messageBroker). 모듈을 얹는 것만으로 서로 충돌하면 안 된다
        ApplicationContextRunner()
            .withBean("messageBrokerTaskScheduler", ThreadPoolTaskScheduler::class.java, { ThreadPoolTaskScheduler().apply { poolSize = 1 } })
            .withBean("notificationWebSocketHeartbeatTaskScheduler", ThreadPoolTaskScheduler::class.java, { ThreadPoolTaskScheduler().apply { poolSize = 1 } })
            .withConfiguration(AutoConfigurations.of(SkeletonSchedulerAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(TaskScheduler::class.java)).containsKey("skeletonTaskScheduler")
            }
    }
}
