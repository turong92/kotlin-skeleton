package dev.sumin.skeleton.scheduler

import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext

class DailyLocalTimeTrigger(
    private val localTime: LocalTime,
    private val zoneId: ZoneId,
    private val clock: Clock = Clock.systemUTC(),
) : Trigger {
    override fun nextExecution(triggerContext: TriggerContext): Instant {
        val reference = triggerContext.lastCompletion()
            ?: triggerContext.lastScheduledExecution()
            ?: Instant.now(clock)
        val now = maxOf(Instant.now(clock), reference)
        val zonedNow = now.atZone(zoneId)
        var next = zonedNow.toLocalDate().atTime(localTime).atZone(zoneId)
        if (!next.toInstant().isAfter(now)) {
            next = next.plusDays(1)
        }
        return next.toInstant()
    }
}
