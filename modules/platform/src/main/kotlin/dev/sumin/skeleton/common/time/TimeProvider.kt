package dev.sumin.skeleton.common.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

fun interface TimeProvider {
    fun now(): Instant

    companion object {
        fun systemUtc(): TimeProvider =
            TimeProvider { truncateToDatabasePrecision(Instant.now()) }

        fun fixed(instant: Instant): TimeProvider =
            TimeProvider { truncateToDatabasePrecision(instant) }

        fun truncateToDatabasePrecision(instant: Instant): Instant =
            instant.truncatedTo(ChronoUnit.MICROS)
    }
}

/**
 * [Clock] 을 받는 코드(창 청소 등)가 같은 시계를 보게 한다 — 따로 시스템 시계를 보면 고정 · 조작된 시각에서
 * 방금 만든 창을 지난 창으로 알고 치운다. 존은 UTC 로 고정(`withZone` 은 자기 자신) — 우리가 다루는 시각은 Instant 다.
 */
fun TimeProvider.asClock(): Clock = TimeProviderClock(this)

private class TimeProviderClock(private val time: TimeProvider) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = time.now()
}
