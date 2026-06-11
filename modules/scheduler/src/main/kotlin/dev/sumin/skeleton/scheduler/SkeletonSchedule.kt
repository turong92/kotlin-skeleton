package dev.sumin.skeleton.scheduler

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

sealed interface SkeletonSchedule {
    data class FixedDelay(
        val interval: Duration,
        val initialDelay: Duration,
    ) : SkeletonSchedule

    data class FixedRate(
        val interval: Duration,
        val initialDelay: Duration,
    ) : SkeletonSchedule

    data class Cron(
        val expression: String,
        val zoneId: ZoneId,
    ) : SkeletonSchedule

    data class FixedLocalTime(
        val localTime: LocalTime,
        val zoneId: ZoneId,
    ) : SkeletonSchedule
}
