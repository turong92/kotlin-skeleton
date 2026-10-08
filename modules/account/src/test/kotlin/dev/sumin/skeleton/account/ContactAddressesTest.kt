package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.contact.ContactAddresses
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `ContactAddresses` (서비스 알림의 받는 주소) 는 **지금 알림을 받아도 되는 계정의 확인된 주소**만 돌려준다 — 정지 · 삭제 중 · 지워진 · 이메일 미확인 계정에는 주소를 주지 않는다.
 * 아직 이 SPI 를 쓰는 소비자(알림 메일 채널)는 이 레포에 없다 — 계약이다 (docs/accounts.md).
 */
class ContactAddressesTest {
    private fun harness(contactOn: Boolean = true) = AccountHarness(
        props = AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
            password = AccountProperties.Password(bcryptStrength = 4),
            contactEmail = AccountProperties.ContactEmail(enabled = contactOn),
        ),
    )

    private fun AccountHarness.withContact(contact: String? = "contact@example.com"): Account {
        val a = activeAccount()
        if (contact != null) repo.update(a.id, AccountPatch(contactEmail = contact), time.now())
        return a
    }

    private fun addresses(h: AccountHarness): ContactAddresses = AccountContactAddresses(h.core)

    @Test
    fun `an active account gets its contact address, else its verified account address`() {
        val h = harness()
        val a = h.withContact()
        assertEquals("contact@example.com", addresses(h).contactEmail(a.id))
        val b = h.activeAccount("bob@example.com")
        assertEquals("bob@example.com", addresses(h).contactEmail(b.id))
    }

    @Test
    fun `a suspended, deleted or erased account gets nothing - the address of someone who cannot be mailed service messages is not handed out`() {
        for (status in listOf(AccountStatus.SUSPENDED, AccountStatus.DELETED)) {
            val h = harness()
            val a = h.withContact()
            h.repo.update(a.id, AccountPatch(status = status, deletedAt = h.time.now(), purgeAfter = h.time.now().plus(Duration.ofDays(30))), h.time.now())
            assertNull(addresses(h).contactEmail(a.id), "$status")
        }
        val h = harness()
        val a = h.withContact()
        h.repo.update(a.id, AccountPatch(status = AccountStatus.SUSPENDED), h.time.now())
        h.repo.erase(a.id, h.time.now(), forced = true)
        assertNull(addresses(h).contactEmail(a.id), "ERASED")
    }

    @Test
    fun `an account whose email is not confirmed yet gets nothing from the account address - and the contact switch off ignores a leftover contact address`() {
        val h = harness()
        val now = h.time.now()
        val unconfirmed = Account("acc_u", "unconfirmed@example.com", false, AccountStatus.ACTIVE, setOf("USER"), "U", null, null, now, now)
        h.repo.insert(unconfirmed, listOf(Identity("idn_u", unconfirmed.id, "password", "unconfirmed@example.com", false, createdAt = now)))
        assertNull(addresses(h).contactEmail(unconfirmed.id), "unconfirmed account address")

        val off = harness(contactOn = false)
        val b = off.withContact("leftover@example.com")
        assertEquals("ann@example.com", addresses(off).contactEmail(b.id), "the leftover contact address is not used while the feature is off")
    }
}
