package dev.sumin.skeleton.scheduler

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class SkeletonExternalSchedulesTest {
    private val resolver = SkeletonScheduleResolver(SkeletonSchedulerProperties(zone = "Asia/Seoul"))

    private fun export(methodName: String): SkeletonExternalSchedule {
        val annotation = Jobs::class.java.getMethod(methodName).getAnnotation(SkeletonScheduled::class.java)
        return SkeletonExternalSchedules.toExternal(SkeletonDiscoveredTask(methodName, resolver.resolve(annotation), annotation))
    }

    @Test
    fun `turns schedules into unix cron with defaults by period`() {
        assertThat(export("nightly")).isEqualTo(SkeletonExternalSchedule("nightly", "30 3 * * *", "Asia/Seoul", 900, 2, 120, listOf("prod")))
        assertThat(export("everyFiveMinutes")).isEqualTo(SkeletonExternalSchedule("everyFiveMinutes", "*/5 * * * *", "UTC", 60, 0, 300, emptyList()))
        assertThat(export("weekdays").cron).isEqualTo("0 9 * * MON-FRI")
        assertThat(export("atTen")).isEqualTo(SkeletonExternalSchedule("atTen", "0 10 * * *", "Asia/Seoul", 120, 1, 30, emptyList()))
        assertThat(export("everySixHours").cron).isEqualTo("0 */6 * * *")
        assertThat(export("wednesdays").cron).isEqualTo("0 9 * * WED")
        assertThat(export("julyFirst").cron).isEqualTo("0 0 1 JUL *")
        assertThat(export("quarterPastStart")).extracting({ it.cron }, { it.budgetSeconds }).containsExactly("5-59/15 * * * *", 60)
        assertThat(export("twiceAMorning").budgetSeconds).isEqualTo(60)   // 9시 · 10시 — 가장 짧은 간격(1시간)으로 가른다, 언제 돌려도 같다
    }

    @Test
    fun `fails instead of silently changing the period`() {
        assertThatThrownBy { export("everySeconds") }.hasMessageContaining("seconds")
        assertThatThrownBy { export("everySevenMinutes") }.hasMessageContaining("evenly")
        assertThatThrownBy { export("lastDay") }.hasMessageContaining("L/W/#")
        assertThatThrownBy { export("firstMondayOnly") }.hasMessageContaining("OR")
    }

    @Suppress("unused")
    class Jobs {
        @SkeletonScheduled(cron = "0 30 3 * * *", profiles = ["prod"]) fun nightly() {}
        @SkeletonScheduled(fixedRate = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES) fun everyFiveMinutes() {}
        @SkeletonScheduled(cron = "0 0 9 ? * MON-FRI") fun weekdays() {}
        @SkeletonScheduled(localTime = "10:00", budgetSeconds = 120, retries = 1, minBackoffSeconds = 30) fun atTen() {}
        @SkeletonScheduled(fixedDelay = 6, timeUnit = java.util.concurrent.TimeUnit.HOURS) fun everySixHours() {}
        @SkeletonScheduled(cron = "*/10 * * * * *") fun everySeconds() {}
        @SkeletonScheduled(fixedRate = 7, timeUnit = java.util.concurrent.TimeUnit.MINUTES) fun everySevenMinutes() {}
        @SkeletonScheduled(cron = "0 0 0 L * *") fun lastDay() {}
        @SkeletonScheduled(cron = "0 0 9 * * WED") fun wednesdays() {}
        @SkeletonScheduled(cron = "0 0 0 1 JUL *") fun julyFirst() {}
        @SkeletonScheduled(cron = "0 5/15 * * * *") fun quarterPastStart() {}
        @SkeletonScheduled(cron = "0 0 9,10 * * *") fun twiceAMorning() {}
        @SkeletonScheduled(cron = "0 0 9 1 * MON") fun firstMondayOnly() {}
    }
}
