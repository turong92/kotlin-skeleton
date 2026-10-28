package dev.sumin.skeleton.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationMuteTest {
    /** 호출을 세는 끄기 저장소 - 목록이 비면 부르지 않는지 본다 */
    private class CountingMutes(private val delegate: NotificationMuteRepository = InMemoryNotificationMuteRepository()) : NotificationMuteRepository by delegate {
        var calls = 0

        override fun mutedAmong(type: String, recipientIds: Set<String>): Set<String> {
            calls += 1
            return delegate.mutedAmong(type, recipientIds)
        }
    }

    private class Fixture(types: List<String> = listOf("NOTE_UPDATED")) {
        val inbox = InMemoryNotificationInboxRepository()
        val mutes = CountingMutes()
        val broker = InMemoryNotificationBroker(inbox, DefaultNotificationRecipientResolver(), mutes, types)
        val heard = mutableListOf<NotificationEvent>()

        init {
            broker.subscribe(emptySet()) { heard += it }
        }

        fun inboxSize(recipient: String) = inbox.findByRecipient(recipient).totalElements
    }

    @Test
    fun `a muted recipient of a mutable type gets neither the row nor the live event`() {
        val f = Fixture()
        f.mutes.setMuted("acc_a", "NOTE_UPDATED", true)

        val result = f.broker.publish(NotificationEvent(topic = "t", type = "NOTE_UPDATED", recipientIds = setOf("acc_a", "acc_b")))

        assertEquals(0, f.inboxSize("acc_a"))
        assertEquals(1, f.inboxSize("acc_b"))
        assertEquals(1, result.deliveredSubscribers)
        assertEquals(setOf("acc_b"), f.heard.single().recipientIds)   // 전달하는 이벤트도 남은 사람으로 줄어든다
    }

    @Test
    fun `an event whose recipients are all muted is neither stored nor delivered - it never turns into a broadcast`() {
        val f = Fixture()
        f.mutes.setMuted("acc_a", "NOTE_UPDATED", true)

        val result = f.broker.publish(NotificationEvent(topic = "t", type = "NOTE_UPDATED", recipientIds = setOf("acc_a")))

        assertEquals(0, f.inboxSize("acc_a"))
        assertEquals(0, result.deliveredSubscribers)
        assertTrue(f.heard.isEmpty())
    }

    @Test
    fun `muting the only listed recipient never widens the event to everyone when the payload names another recipient`() {
        val f = Fixture()
        f.mutes.setMuted("acc_a", "NOTE_UPDATED", true)

        f.broker.publish(NotificationEvent(topic = "t", type = "NOTE_UPDATED", recipientIds = setOf("acc_a"), payload = mapOf("userId" to "acc_b")))

        assertEquals(setOf("acc_b"), f.heard.single().recipientIds)
        assertEquals(1, f.inboxSize("acc_b"))
    }

    @Test
    fun `a type outside the mutable list ignores any mute row`() {
        val f = Fixture()
        f.mutes.setMuted("acc_a", "SECURITY_ALERT", true)

        f.broker.publish(NotificationEvent(topic = "t", type = "SECURITY_ALERT", recipientIds = setOf("acc_a")))

        assertEquals(1, f.inboxSize("acc_a"))
        assertEquals(1, f.heard.size)
        assertEquals(0, f.mutes.calls)
    }

    @Test
    fun `a broadcast with no recipients cannot be muted`() {
        val f = Fixture()
        f.mutes.setMuted("acc_a", "NOTE_UPDATED", true)

        val result = f.broker.publish(NotificationEvent(topic = "t", type = "NOTE_UPDATED"))

        assertEquals(1, result.deliveredSubscribers)
        assertEquals(emptySet(), f.heard.single().recipientIds)
    }

    @Test
    fun `an empty mutable list never consults the mute repository`() {
        val f = Fixture(types = emptyList())
        f.mutes.setMuted("acc_a", "NOTE_UPDATED", true)

        f.broker.publish(NotificationEvent(topic = "t", type = "NOTE_UPDATED", recipientIds = setOf("acc_a")))

        assertEquals(0, f.mutes.calls)
        assertEquals(1, f.inboxSize("acc_a"))
    }

    @Test
    fun `unmuting brings notifications back`() {
        val f = Fixture()
        f.mutes.setMuted("acc_a", "NOTE_UPDATED", true)
        f.mutes.setMuted("acc_a", "NOTE_UPDATED", false)

        f.broker.publish(NotificationEvent(topic = "t", type = "NOTE_UPDATED", recipientIds = setOf("acc_a")))

        assertEquals(1, f.inboxSize("acc_a"))
    }

    @Test
    fun `in memory mutes are per project`() {
        fun project(id: String) = dev.sumin.skeleton.common.project.Project(id, listOf("$id.test"), id, dev.sumin.skeleton.common.project.ProjectDatasource(), emptyMap())
        val mutes = InMemoryNotificationMuteRepository()
        dev.sumin.skeleton.common.project.ProjectContext.runIn(project("a")) { mutes.setMuted("acc_u", "T", true) }

        assertEquals(setOf("T"), dev.sumin.skeleton.common.project.ProjectContext.runIn(project("a")) { mutes.mutedTypes("acc_u") })
        assertEquals(emptySet(), dev.sumin.skeleton.common.project.ProjectContext.runIn(project("b")) { mutes.mutedTypes("acc_u") })
        assertEquals(setOf("acc_u"), dev.sumin.skeleton.common.project.ProjectContext.runIn(project("a")) { mutes.mutedAmong("T", setOf("acc_u", "acc_v")) })
    }

    @Test
    fun `properties trim blanks and duplicates but keep configured order`() {
        assertEquals(listOf("b", "a"), NotificationPreferencesProperties(listOf(" b ", "", "a", "b")).mutableTypes)
        assertEquals(emptyList(), NotificationPreferencesProperties().mutableTypes)
    }
}
