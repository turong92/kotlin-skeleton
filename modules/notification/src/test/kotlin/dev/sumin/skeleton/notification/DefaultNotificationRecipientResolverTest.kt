package dev.sumin.skeleton.notification

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultNotificationRecipientResolverTest {
    private val resolver = DefaultNotificationRecipientResolver()

    @Test
    fun `resolves explicit recipients before payload fallbacks`() {
        val event = NotificationEvent(
            topic = "demo",
            type = "created",
            recipientIds = setOf("acc_user"),
            payload = mapOf(
                "userId" to "acc_admin",
                "recipientIds" to listOf("acc_support", " "),
            ),
        )

        val recipients = resolver.resolveRecipients(event)

        assertEquals(setOf("acc_user", "acc_admin", "acc_support"), recipients)
    }
}
