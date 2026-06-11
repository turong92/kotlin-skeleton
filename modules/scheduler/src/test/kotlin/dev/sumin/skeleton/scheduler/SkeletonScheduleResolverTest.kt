package dev.sumin.skeleton.scheduler

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class SkeletonScheduleResolverTest {
    private val resolver = SkeletonScheduleResolver(SkeletonSchedulerProperties(zone = "UTC"))

    @Test
    fun `resolves fixed delay using annotation time unit`() {
        val schedule = resolver.resolve(annotation("fixedDelay"))

        assertThat(schedule).isEqualTo(
            SkeletonSchedule.FixedDelay(
                interval = Duration.ofSeconds(5),
                initialDelay = Duration.ofSeconds(2),
            ),
        )
    }

    @Test
    fun `resolves fixed rate using annotation time unit`() {
        val schedule = resolver.resolve(annotation("fixedRate"))

        assertThat(schedule).isEqualTo(
            SkeletonSchedule.FixedRate(
                interval = Duration.ofMinutes(3),
                initialDelay = Duration.ZERO,
            ),
        )
    }

    @Test
    fun `resolves cron with annotation zone`() {
        val schedule = resolver.resolve(annotation("cron")) as SkeletonSchedule.Cron

        assertThat(schedule.expression).isEqualTo("0 15 9 * * *")
        assertThat(schedule.zoneId).isEqualTo(ZoneId.of("Asia/Seoul"))
    }

    @Test
    fun `resolves fixed local time with timezone alias`() {
        val schedule = resolver.resolve(annotation("localTime")) as SkeletonSchedule.FixedLocalTime

        assertThat(schedule.localTime).isEqualTo(LocalTime.of(7, 30))
        assertThat(schedule.zoneId).isEqualTo(ZoneId.of("America/New_York"))
    }

    @Test
    fun `rejects annotations with more than one schedule mode`() {
        assertThatThrownBy { resolver.resolve(annotation("invalidMultipleModes")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("exactly one schedule mode")
    }

    private fun annotation(methodName: String): SkeletonScheduled =
        SampleSchedules::class.java.getDeclaredMethod(methodName).getAnnotation(SkeletonScheduled::class.java)

    private class SampleSchedules {
        @SkeletonScheduled(fixedDelay = 5, initialDelay = 2, timeUnit = TimeUnit.SECONDS)
        fun fixedDelay() = Unit

        @SkeletonScheduled(fixedRate = 3, timeUnit = TimeUnit.MINUTES)
        fun fixedRate() = Unit

        @SkeletonScheduled(cron = "0 15 9 * * *", zone = "Asia/Seoul")
        fun cron() = Unit

        @SkeletonScheduled(localTime = "07:30", timeZone = "America/New_York")
        fun localTime() = Unit

        @SkeletonScheduled(fixedRate = 1, cron = "0 * * * * *")
        fun invalidMultipleModes() = Unit
    }
}
