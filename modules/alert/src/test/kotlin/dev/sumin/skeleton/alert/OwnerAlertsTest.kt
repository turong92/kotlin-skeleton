package dev.sumin.skeleton.alert

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class OwnerAlertsTest {
    private class Clock(var now: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider {
        override fun now() = now
    }

    private class Recording(override val name: String = "recording", private val failFirst: Int = 0) : AlertChannel {
        val sent = CopyOnWriteArrayList<AlertMessage>()
        var calls = 0
        override fun send(message: AlertMessage) {
            calls++
            if (calls <= failFirst) throw AlertDeliveryException("down")
            sent += message
        }
    }

    /** 앱이 자기 종류를 더한다 — AlertKind 는 닫힌 enum 이 아니다 */
    private enum class ShopKind(override val severity: AlertSeverity, override val title: String, override val minInterval: Duration = Duration.ZERO) : AlertKind {
        ORDER_STUCK(AlertSeverity.WARN, "An order is stuck", Duration.ofMinutes(10)),
    }

    private val clock = Clock()
    private val channel = Recording()
    private fun alerts(
        store: AlertStore = InMemoryAlertStore(),
        channels: List<AlertChannel> = listOf(channel),
        properties: AlertProperties = AlertProperties(),
    ) = DefaultOwnerAlerts(store, channels, properties, clock, handoff = { it.run() }, pause = {})

    @Test
    fun `an app-defined kind is delivered with its own title and severity`() {
        alerts().emit(ShopKind.ORDER_STUCK, key = "order-7", detail = "waiting for payment")

        val message = channel.sent.single()
        assertEquals("ORDER_STUCK", message.kind)
        assertEquals("order-7", message.key)
        assertEquals(AlertSeverity.WARN, message.severity)
        assertEquals("An order is stuck", message.title)
    }

    @Test
    fun `the same kind and key inside the interval is folded and the next send carries the suppressed count`() {
        val alerts = alerts()
        alerts.emit(ShopKind.ORDER_STUCK, "order-7")
        clock.now = clock.now.plusSeconds(60)
        alerts.emit(ShopKind.ORDER_STUCK, "order-7")
        alerts.emit(ShopKind.ORDER_STUCK, "order-7")
        alerts.emit(ShopKind.ORDER_STUCK, "order-8") // another key is independent
        assertEquals(listOf("order-7", "order-8"), channel.sent.map { it.key })

        clock.now = clock.now.plus(Duration.ofMinutes(10))
        alerts.emit(ShopKind.ORDER_STUCK, "order-7")

        assertEquals(3, channel.sent.size)
        assertEquals(2, channel.sent.last().suppressed)
    }

    @Test
    fun `the interval can be overridden per kind by name`() {
        val alerts = alerts(properties = AlertProperties(intervals = mapOf("order-stuck" to Duration.ZERO)))
        alerts.emit(ShopKind.ORDER_STUCK, "k")
        alerts.emit(ShopKind.ORDER_STUCK, "k")
        assertEquals(2, channel.sent.size)
    }

    @Test
    fun `detail is masked, collapsed to one line and cut to 1000 characters`() {
        alerts().emit(BuiltInAlertKind.TEST, detail = "call failed\nBearer abc.def.ghi password=hunter2 " + "x".repeat(2000))

        val detail = channel.sent.single().detail
        assertFalse(detail.contains("hunter2") || detail.contains("abc.def.ghi") || detail.contains("\n"), detail)
        assertEquals(1000, detail.length)
    }

    @Test
    fun `emit never throws - a failing store or channel only logs`() {
        val brokenStore = object : AlertStore { override fun record(kind: AlertKind, key: String, severity: AlertSeverity, title: String, detail: String, now: Instant, minInterval: Duration): AlertRecorded = error("db down") }
        alerts(store = brokenStore).emit(BuiltInAlertKind.TEST)
        alerts(channels = listOf(Recording(failFirst = 99))).emit(BuiltInAlertKind.TEST)
    }

    @Test
    fun `a failing channel is retried, and one failing channel does not stop the others`() {
        val flaky = Recording("flaky", failFirst = 2)
        val dead = Recording("dead", failFirst = 99)
        alerts(channels = listOf(dead, flaky, channel)).emit(BuiltInAlertKind.TEST)

        assertEquals(1, flaky.sent.size) // third attempt
        assertEquals(3, dead.calls) // attempts = 3
        assertEquals(1, channel.sent.size)
    }

    @Test
    fun `inside a transaction the alert goes out after commit, and not at all after rollback`() {
        val alerts = alerts()
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            alerts.emit(BuiltInAlertKind.TEST, "after-commit")
            assertTrue(channel.sent.isEmpty(), "must wait for commit")
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit)
            assertEquals(listOf("after-commit"), channel.sent.map { it.key })

            alerts.emit(BuiltInAlertKind.TEST, "rolled-back")
            // 커밋 없이 끝(롤백) — afterCommit 이 불리지 않는다
            assertEquals(1, channel.sent.size)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun `immediate bypasses the transaction so a failure alert survives a rollback`() {
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            alerts().emit(BuiltInAlertKind.TEST, "now", immediate = true)
            assertEquals(1, channel.sent.size)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun `NONE does nothing`() {
        OwnerAlerts.NONE.emit(BuiltInAlertKind.TEST)
    }
}
