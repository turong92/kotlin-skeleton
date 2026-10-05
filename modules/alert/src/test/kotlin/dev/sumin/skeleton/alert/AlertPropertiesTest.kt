package dev.sumin.skeleton.alert

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertPropertiesTest {
    @Test
    fun `only an http(s) address with a host and no user info is usable`() {
        assertNotNull(AlertProperties(webhookUrl = "https://discord.com/api/webhooks/1/abc").webhookUri)
        assertNull(AlertProperties(webhookUrl = "").webhookUri)
        assertNull(AlertProperties(webhookUrl = "ftp://x/y").webhookUri)
        assertNull(AlertProperties(webhookUrl = "https://user:pw@host/x").webhookUri)
        assertNull(AlertProperties(webhookUrl = "not a url").webhookUri)
        assertTrue(AlertProperties(webhookUrl = "ftp://x/y").webhookInvalid)
        assertFalse(AlertProperties().webhookInvalid)
    }

    @Test
    fun `toString never prints the webhook address or recipients`() {
        val text = AlertProperties(webhookUrl = "https://discord.com/api/webhooks/1/SECRET", mailTo = "me@example.com").toString()
        assertFalse(text.contains("SECRET") || text.contains("me@example.com"), text)
    }

    @Test
    fun `recipients are split on comma or semicolon, junk dropped`() {
        assertEquals(listOf("a@x.com", "b@y.com"), AlertProperties(mailTo = "a@x.com; b@y.com, nope").mailRecipients)
    }

    @Test
    fun `interval override matches the kind name in any case or separator`() {
        val p = AlertProperties(intervals = mapOf("job-dead" to Duration.ofHours(2)))
        assertEquals(Duration.ofHours(2), p.interval(BuiltInAlertKind.JOB_DEAD))
        assertEquals(BuiltInAlertKind.TEST.minInterval, p.interval(BuiltInAlertKind.TEST))
    }
}
