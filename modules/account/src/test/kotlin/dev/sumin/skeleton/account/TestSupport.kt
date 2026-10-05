package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant

class MutableTime(var at: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = at
    fun advance(d: Duration) { at = at.plus(d) }
}
