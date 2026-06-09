package dev.sumin.skeleton.common.time

import java.time.Instant
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
