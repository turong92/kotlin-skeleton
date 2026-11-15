package dev.sumin.skeleton.notification.advertising

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSendResult
import dev.sumin.skeleton.notification.mail.MailSender
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 광고 종류로 표시된 메일이 동의 확인 없이 나가는 길이 없다 - 같은 게이트(동의 · 야간 보류 · (광고) 표기 · 수신 거부 문구) */
class AdvertisingMailSenderTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val suffix = "수신 거부: 설정 > 알림"
    private val properties = AdvertisingProperties(types = listOf("promo.tips"), fallbackZone = "Asia/Seoul", senderName = "Example Co.", bodySuffix = suffix)

    private class Outbox : MailSender {
        val sent = mutableListOf<MailMessage>()
        override fun send(message: MailMessage): MailSendResult {
            sent += message
            return MailSendResult(accepted = true)
        }
    }

    private val outbox = Outbox()

    private fun seoulAt(date: String, hour: Int): Instant = LocalDate.parse(date).atTime(hour, 0).atZone(seoul).toInstant()

    private fun sender(consents: Map<String, AdvertisingConsent>, now: Instant = seoulAt("2026-11-02", 12)): MailSender {
        val gate = AdvertisingGate(properties, AdvertisingConsentPolicy { id, _ -> consents[id] ?: AdvertisingConsent(send = false, night = false) }, null, TimeProvider.fixed(now))
        return AdvertisingMailSender(outbox) { gate }
    }

    private fun ad(account: String? = "acc_a", to: List<String> = listOf("a@example.com"), type: String? = "promo.tips", html: String? = null) =
        MailMessage(to = to, subject = "새 기능", text = "본문", html = html, notificationType = type, accountId = account)

    @Test
    fun `an advertising mail to someone without a valid consent is not sent`() {
        val result = sender(emptyMap()).send(ad())

        assertFalse(result.accepted)
        assertNull(result.heldUntil)
        assertTrue(outbox.sent.isEmpty())
    }

    @Test
    fun `at night without the night consent it is held until the night ends, not sent`() {
        val result = sender(mapOf("acc_a" to AdvertisingConsent(send = true, night = false)), now = seoulAt("2026-11-02", 22)).send(ad())

        assertFalse(result.accepted)
        assertEquals(seoulAt("2026-11-03", 8), result.heldUntil)
        assertTrue(outbox.sent.isEmpty())
    }

    @Test
    fun `with consent it is sent with the advertising mark in the subject and the opt-out in the text and the html`() {
        val result = sender(mapOf("acc_a" to AdvertisingConsent(send = true, night = false))).send(ad(html = "<p>본문</p>"))

        assertTrue(result.accepted)
        val sent = outbox.sent.single()
        assertEquals("(광고) 새 기능", sent.subject)
        assertTrue(sent.text.endsWith(suffix), sent.text)
        assertTrue(sent.html!!.contains("수신 거부: 설정 &gt; 알림"), sent.html)
        assertEquals(listOf("a@example.com"), sent.to)
    }

    @Test
    fun `a mail that is not an advertising type goes out untouched`() {
        val plain = ad(type = "account.verify", account = null)

        assertTrue(sender(emptyMap()).send(plain).accepted)
        assertEquals(plain, outbox.sent.single())
        assertTrue(sender(emptyMap()).send(ad(type = null, account = null)).accepted)
    }

    @Test
    fun `an advertising mail that cannot name one account is refused - the consent cannot be checked`() {
        val consenting = mapOf("acc_a" to AdvertisingConsent(send = true, night = false))

        assertFalse(sender(consenting).send(ad(account = null)).accepted)
        assertFalse(sender(consenting).send(ad(to = listOf("a@example.com", "b@example.com"))).accepted)
        assertTrue(outbox.sent.isEmpty())
    }
}
