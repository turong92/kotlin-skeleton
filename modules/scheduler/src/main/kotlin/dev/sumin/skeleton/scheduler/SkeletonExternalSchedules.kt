package dev.sumin.skeleton.scheduler

import java.time.Duration
import java.time.ZonedDateTime
import org.springframework.scheduling.support.CronExpression

/**
 * 밖의 스케줄러(Cloud Scheduler 등)에 만들 작업 한 줄. cron 은 unix 5자리(분 시 일 월 요일)이고 [zone] 시간대로 읽는다.
 * [profiles] 는 어노테이션의 `profiles` 그대로 — 비어 있지 않으면 그 프로필 환경에만 만든다(다른 환경에서 부르면 404).
 */
data class SkeletonExternalSchedule(
    val taskId: String,
    val cron: String,
    val zone: String,
    val budgetSeconds: Int,
    val retries: Int,
    val minBackoffSeconds: Int,
    val profiles: List<String>,
)

/**
 * `@SkeletonScheduled` 작업들을 밖의 스케줄러 목록으로 바꾼다 — 앱 테스트에서 불러 JSON 으로 써 두고 인프라(terraform `for_each` 등)가 읽게 한다 (docs/modules/scheduler.md).
 * unix cron 으로 옮길 수 없는 스케줄(초 단위 · 분/시로 나눠떨어지지 않는 간격 · `L` `W` `#`)은 실패한다 — 몰래 다른 주기로 돌지 않게.
 */
object SkeletonExternalSchedules {
    private val SHORT_PERIOD = Duration.ofHours(1)

    fun from(tasks: List<SkeletonDiscoveredTask>): List<SkeletonExternalSchedule> =
        tasks.sortedBy { it.taskId }.map(::toExternal)

    fun toExternal(task: SkeletonDiscoveredTask): SkeletonExternalSchedule {
        require('/' !in task.taskId) { "taskId=${task.taskId}: '/' cannot be a single path segment of the task address" }
        val (cron, zone) = unixCron(task.taskId, task.schedule)
        val short = period(task.schedule) <= SHORT_PERIOD
        val annotation = task.annotation
        return SkeletonExternalSchedule(
            taskId = task.taskId,
            cron = cron,
            zone = zone,
            budgetSeconds = annotation.budgetSeconds.orDefault(if (short) 60 else 900),
            retries = annotation.retries.orDefault(if (short) 0 else 2),
            minBackoffSeconds = annotation.minBackoffSeconds.orDefault(if (short) 300 else 120),
            profiles = annotation.profiles.toList(),
        )
    }

    private fun Int.orDefault(default: Int) = if (this < 0) default else this

    private fun unixCron(taskId: String, schedule: SkeletonSchedule): Pair<String, String> = when (schedule) {
        is SkeletonSchedule.Cron -> fromSpringCron(taskId, schedule.expression) to schedule.zoneId.id
        is SkeletonSchedule.FixedLocalTime -> {
            require(schedule.localTime.second == 0 && schedule.localTime.nano == 0) { "taskId=$taskId: localTime with seconds has no unix cron" }
            "${schedule.localTime.minute} ${schedule.localTime.hour} * * *" to schedule.zoneId.id
        }
        is SkeletonSchedule.FixedDelay -> fromInterval(taskId, schedule.interval) to "UTC"
        is SkeletonSchedule.FixedRate -> fromInterval(taskId, schedule.interval) to "UTC"
    }

    private fun fromInterval(taskId: String, interval: Duration): String {
        val minutes = interval.toMinutes()
        require(interval == Duration.ofMinutes(minutes) && minutes > 0) { "taskId=$taskId: interval $interval is not whole minutes" }
        return when {
            minutes == 1L -> "* * * * *"
            minutes < 60 && 60 % minutes == 0L -> "*/$minutes * * * *"
            minutes == 60L -> "0 * * * *"
            minutes == 1440L -> "0 0 * * *"
            minutes % 60 == 0L && minutes < 1440 && 24 % (minutes / 60) == 0L -> "0 */${minutes / 60} * * *"
            else -> throw IllegalArgumentException("taskId=$taskId: interval $interval does not divide an hour or a day evenly")
        }
    }

    private val MACROS = mapOf(
        "@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *", "@monthly" to "0 0 1 * *",
        "@weekly" to "0 0 * * 0", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *", "@hourly" to "0 * * * *",
    )

    /** 스프링 6자리(초 분 시 일 월 요일) → unix 5자리. 초는 0 이어야 하고 `?` 는 `*` 로 */
    internal fun fromSpringCron(taskId: String, expression: String): String {
        MACROS[expression.lowercase()]?.let { return it }
        val fields = expression.trim().split(Regex("\\s+"))
        require(fields.size == 6) { "taskId=$taskId: cron '$expression' must have 6 fields" }
        require(fields[0] == "0") { "taskId=$taskId: cron '$expression' runs at seconds other than 0 — no unix cron for that" }
        val rest = fields.drop(1).map { if (it == "?") "*" else it }
        // 영문 이름(WED · JUL …)의 글자는 빼고 본다
        require(rest.none { f -> f.replace(Regex("[A-Za-z]{3}"), "").any { it == 'L' || it == 'W' || it == '#' } }) {
            "taskId=$taskId: cron '$expression' uses L/W/# — not in unix cron"
        }
        // 스프링은 일과 요일을 둘 다 맞춰야(AND) 돌고, unix cron 은 둘 다 적히면 하나만 맞아도(OR) 돈다 — 옮기면 주기가 바뀐다
        require(rest[2] == "*" || rest[4] == "*") { "taskId=$taskId: cron '$expression' sets both day-of-month and day-of-week — unix cron reads that as OR" }
        // 스프링의 `5/15`(시작/간격)는 unix cron 에서 `5-59/15` 로 적는다
        return rest.mapIndexed { i, field ->
            field.split(',').joinToString(",") { part ->
                Regex("^(\\d+)/(\\d+)$").matchEntire(part)?.let { "${it.groupValues[1]}-${FIELD_MAX[i]}/${it.groupValues[2]}" } ?: part
            }
        }.joinToString(" ")
    }

    private val FIELD_MAX = listOf(59, 23, 31, 12, 6)   // 분 시 일 월 요일

    /** 도는 간격 중 가장 짧은 것 — 짧은 주기(1시간 이하) 작업과 긴 작업의 기본값을 가른다. 고정 기준 시각에서 재서 언제 돌려도 같은 답이다 */
    private fun period(schedule: SkeletonSchedule): Duration = when (schedule) {
        is SkeletonSchedule.FixedDelay -> schedule.interval
        is SkeletonSchedule.FixedRate -> schedule.interval
        is SkeletonSchedule.FixedLocalTime -> Duration.ofDays(1)
        is SkeletonSchedule.Cron -> {
            val cron = CronExpression.parse(schedule.expression)
            var previous = requireNotNull(cron.next(ZonedDateTime.of(2001, 1, 1, 0, 0, 0, 0, schedule.zoneId)))
            var shortest = Duration.ofDays(366)
            repeat(48) {
                val next = cron.next(previous) ?: return@repeat
                shortest = minOf(shortest, Duration.between(previous, next))
                previous = next
            }
            shortest
        }
    }
}
