package dev.sumin.skeleton.scheduler

import java.time.Duration
import java.time.LocalTime

class SkeletonScheduleResolver(
    private val properties: SkeletonSchedulerProperties,
) {
    fun resolve(annotation: SkeletonScheduled): SkeletonSchedule {
        val modes = listOfNotNull(
            annotation.fixedDelay.takeIf { it >= 0 }?.let { ScheduleMode.FIXED_DELAY },
            annotation.fixedRate.takeIf { it >= 0 }?.let { ScheduleMode.FIXED_RATE },
            annotation.cron.takeIf { it.isNotBlank() }?.let { ScheduleMode.CRON },
            annotation.localTime.takeIf { it.isNotBlank() }?.let { ScheduleMode.LOCAL_TIME },
        )
        require(modes.size == 1) {
            "@SkeletonScheduled must declare exactly one schedule mode: fixedDelay, fixedRate, cron, or localTime"
        }

        return when (modes.single()) {
            ScheduleMode.FIXED_DELAY -> SkeletonSchedule.FixedDelay(
                interval = annotation.duration(annotation.fixedDelay, "fixedDelay"),
                initialDelay = annotation.initialDelayDuration(),
            )
            ScheduleMode.FIXED_RATE -> SkeletonSchedule.FixedRate(
                interval = annotation.duration(annotation.fixedRate, "fixedRate"),
                initialDelay = annotation.initialDelayDuration(),
            )
            ScheduleMode.CRON -> SkeletonSchedule.Cron(
                expression = annotation.cron.trim(),
                zoneId = annotation.zoneId(),
            )
            ScheduleMode.LOCAL_TIME -> SkeletonSchedule.FixedLocalTime(
                localTime = LocalTime.parse(annotation.localTime.trim()),
                zoneId = annotation.zoneId(),
            )
        }
    }

    private fun SkeletonScheduled.zoneId() =
        if (zone.isNotBlank() || timeZone.isNotBlank()) {
            SkeletonSchedulerProperties.zoneId(zone = zone, timeZone = timeZone)
        } else {
            properties.defaultZoneId()
        }

    private fun SkeletonScheduled.duration(
        value: Long,
        name: String,
    ): Duration {
        require(value > 0) { "$name must be greater than zero" }
        return Duration.ofMillis(timeUnit.toMillis(value))
    }

    private fun SkeletonScheduled.initialDelayDuration(): Duration {
        require(initialDelay >= 0) { "initialDelay must be zero or greater" }
        return Duration.ofMillis(timeUnit.toMillis(initialDelay))
    }

    private enum class ScheduleMode {
        FIXED_DELAY,
        FIXED_RATE,
        CRON,
        LOCAL_TIME,
    }
}
