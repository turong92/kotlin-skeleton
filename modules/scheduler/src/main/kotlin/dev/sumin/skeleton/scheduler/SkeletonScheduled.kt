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
    // 밖에서 부를 때(mode=EXTERNAL)의 호출자 설정 — 앱은 쓰지 않고 작업 목록 내보내기(SkeletonExternalSchedules)에만 실린다. -1 = 기본값
    // (주기 1시간 이하: 예산 60초 · 재시도 0 · 대기 300초, 그보다 길면: 900초 · 2 · 120초)
    val budgetSeconds: Int = -1,
    val retries: Int = -1,
    val minBackoffSeconds: Int = -1,
)
