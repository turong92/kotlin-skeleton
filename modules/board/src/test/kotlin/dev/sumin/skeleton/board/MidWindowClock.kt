package dev.sumin.skeleton.board

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 한도 시험이 쓰는 고정 시계 - 1분 · 10분 고정 창의 **한가운데**(00:05:30)에 세운다. 실제 시계를 쓰면 시험이 창 경계에 걸릴 때(드물게) 센 횟수가
 * 0 으로 돌아가 「열한 번째는 429」 같은 단언이 흔들린다.
 */
object MidWindowClock {
    val NOW: Instant = Instant.parse("2026-03-01T00:05:30Z")
    val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
}
