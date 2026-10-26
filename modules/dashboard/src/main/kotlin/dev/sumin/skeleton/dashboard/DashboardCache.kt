package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.project.ProjectContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** [value] 와, 그 값을 계산한 시각 */
data class Cached<T>(val value: T, val computedAt: Instant)

/**
 * 짧은 메모리 캐시. 키는 프로젝트마다 따로(`ProjectContext.scopedKey`)이고, 같은 키가 동시에 와도 계산은 한 번이다 (single-flight) —
 * 운영자 열 명이 동시에 열어도 DB · 외부 호출은 캐시 주기당 한 번. [cacheable] 이 false 인 값(오류)은 담지 않는다.
 * [ttl] 이 0 이면 캐시하지 않고 늘 계산한다.
 */
class DashboardCache(
    private val ttl: Duration,
    private val now: () -> Instant = Instant::now,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Slot {
        var value: Any? = null
        var computedAt: Instant? = null

        // sweep 이 잠금 없이 읽는다
        @Volatile var expiresAtNanos: Long = 0

        @Volatile var filled = false
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, cacheable: (T) -> Boolean = { true }, compute: () -> T): Cached<T> {
        if (ttl.isZero || ttl.isNegative) return Cached(compute(), now())
        val scoped = ProjectContext.scopedKey(key)
        val slot = slots.computeIfAbsent(scoped) { Slot() }
        synchronized(slot) {
            if (slot.filled && nanoTime() - slot.expiresAtNanos < 0) return Cached(slot.value as T, slot.computedAt!!)
            val value = compute()
            val at = now()
            if (cacheable(value)) {
                slot.value = value
                slot.computedAt = at
                slot.expiresAtNanos = nanoTime() + ttl.toNanos()
                slot.filled = true
            } else {
                slot.filled = false
            }
            if (slots.size > SWEEP_ABOVE) sweep()
            return Cached(value, at)
        }
    }

    /** 범위를 바꿔 가며 부르면 키가 늘어난다 — 많아지면 만료된 칸을 치운다 */
    private fun sweep() {
        val nowNanos = nanoTime()
        slots.entries.removeIf { (_, slot) -> slot.filled && nowNanos - slot.expiresAtNanos >= 0 }
    }

    private companion object {
        const val SWEEP_ABOVE = 2000
    }
}
