package dev.sumin.skeleton.badge

import dev.sumin.skeleton.badge.notification.NotificationBadgeNotifier
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublishResult
import dev.sumin.skeleton.notification.NotificationPublisher
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class BadgeNotificationTest {
    private val sent = ArrayList<NotificationEvent>()
    private val publisher = NotificationPublisher { event -> sent += event; NotificationPublishResult(event.id, 0) }
    private val store = FakeBadgeStore()
    private val time = MutableTime()
    private val definitions = BadgeDefinitions(store, time)

    @Test
    fun `an app award lands in the inbox once, with the badge link and an id made from the event`() {
        val awards = BadgeAwards(store, time) { NotificationBadgeNotifier(publisher, "badges", Locale.KOREAN) }
        definitions.create(BadgeDefinitionInput("first-note", "첫 노트", "", null, false, 0))
        awards.award("acc_1", "first-note", "note:1")
        awards.award("acc_1", "first-note", "note:1")
        val event = sent.single()
        assertEquals("badges", event.topic)
        assertEquals(setOf("acc_1"), event.recipientIds)
        assertEquals("첫 노트", event.title)
        assertEquals("배지를 받았어요", event.message)
        assertEquals("/rewards/badges", event.link)
        assertEquals("bdg_${store.events.single().id}", event.id)
    }
}
