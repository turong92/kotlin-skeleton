package dev.sumin.skeleton.scheduler

import java.util.concurrent.TimeUnit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SkeletonScheduled(
    val id: String = "",
    val fixedDelay: Long = -1,
    val fixedRate: Long = -1,
    val cron: String = "",
    val localTime: String = "",
    val zone: String = "",
    val timeZone: String = "",
    val initialDelay: Long = 0,
    val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    val profiles: Array<String> = [],
    val enabledProperty: String = "",
    val lockKey: String = "",
)
