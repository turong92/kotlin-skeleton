package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.NotificationDeliveryHooks
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PushOpenReporterTest {
    private val now = Instant.parse("2026-11-02T01:00:00Z")
    private val secret = "s".repeat(40)
    private val devices = FakePushDeviceStore()
    private val store = FakePushOpenStore()
    private val hook = RecordingDeliveryHook()
    private val tokens = PushOpenTokens(secret)
    private val reporter = PushOpenReporter(tokens, devices, store, NotificationDeliveryHooks(hook), TimeProvider.fixed(now))

    @Test
    fun `reporting the same click twice counts once, and the token carries no account id`() {
        val device = devices.add("acc_a")
        val token = tokens.issue("evt_1", device)

        reporter.report("evt_1", token)
        reporter.report("evt_1", token)

        assertEquals(setOf("evt_1" to "acc_a"), store.rows)
        assertEquals(listOf("evt_1" to "push"), hook.opened.toList())
        assertTrue("acc_a" !in token)
    }

    @Test
    fun `a forged token, another event id and a device that changed owner are refused and counted nowhere`() {
        val device = devices.add("acc_a")
        val token = tokens.issue("evt_1", device)

        assertFailsWith<PushException> { reporter.report("evt_1", token.dropLast(2) + "AA") }               // 서명 틀림
        assertFailsWith<PushException> { reporter.report("evt_2", token) }                                    // 다른 공지 id
        assertFailsWith<PushException> { reporter.report("evt_1", PushOpenTokens("x".repeat(40)).issue("evt_1", device)) }   // 다른 비밀
        assertFailsWith<PushException> { reporter.report("evt_1", "garbage") }
        devices.devices.clear()
        val taken = devices.add("acc_b")                                                                      // 같은 기기 id, 새 주인
        assertEquals(device.id, taken.id)
        assertFailsWith<PushException> { reporter.report("evt_1", token) }

        assertTrue(store.rows.isEmpty())
        assertTrue(hook.opened.isEmpty())
    }

    @Test
    fun `a device that no longer exists is dropped quietly`() {
        val device = devices.add("acc_a")
        val token = tokens.issue("evt_1", device)
        devices.devices.clear()

        reporter.report("evt_1", token)

        assertTrue(store.rows.isEmpty())
    }
}
