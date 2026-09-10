package dev.sumin.skeleton.timetest

import dev.sumin.skeleton.time.TimeContext
import dev.sumin.skeleton.time.TimeFormatter
import dev.sumin.skeleton.time.ZonedMoment
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@SpringBootApplication
class TimeTestApplication

data class Probe(val now: Instant, val zone: String, val locale: String, val birthday: LocalDate, val deadline: ZonedMoment)

@RestController
class ProbeController(private val ctx: TimeContext, private val fmt: TimeFormatter) {
    @GetMapping("/probe")
    fun probe() = Probe(
        now = ctx.now(),
        zone = ctx.zone().id,
        locale = ctx.locale().toLanguageTag(),
        birthday = LocalDate.of(1998, 3, 5),
        deadline = ZonedMoment.of(LocalDateTime.parse("2026-10-05T21:00"), "Asia/Seoul"),
    )

    @GetMapping("/dual")
    fun dual() = fmt.dual(ZonedMoment.of(LocalDateTime.parse("2026-10-05T21:00"), "Asia/Seoul"))
}
