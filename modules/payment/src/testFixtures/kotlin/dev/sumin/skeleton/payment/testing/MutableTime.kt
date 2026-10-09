package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** 손으로 돌리는 시계. */
class MutableTime(start: Instant = PaymentTestOrders.T0) : TimeProvider {
    private val now = AtomicReference(start)

    override fun now(): Instant = now.get()

    fun advance(by: Duration) {
        now.updateAndGet { it.plus(by) }
    }

    fun set(instant: Instant) {
        now.set(instant)
    }
}
