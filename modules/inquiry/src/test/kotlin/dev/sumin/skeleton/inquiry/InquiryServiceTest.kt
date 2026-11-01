package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InquiryServiceTest {
    private var now = Instant.parse("2026-11-01T00:00:00Z")
    private val time = TimeProvider { now }
    private val repository = InMemoryInquiryRepository()
    private val received = mutableListOf<Inquiry>()
    private var humanOk = true
    private val properties = InquiryProperties(perClient = 2, perAccount = 3)

    private fun service(listener: InquiryListener = InquiryListener { received += it }) =
        InquiryService(repository, { _, _ -> humanOk }, listOf(listener), properties, time)

    private fun request(body: String = "도움말에서 못 찾은 질문이 있어요", extra: InquiryRequest.() -> InquiryRequest = { this }) =
        InquiryRequest(kind = "QUESTION", body = body, lang = "ko", captchaToken = "tok").extra()

    @Test
    fun `anonymous inquiry is stored without an account and listeners hear about it`() {
        val saved = service().submit(request { copy(replyEmail = " a@b.co ", context = "page:AbCdEfGhIjK") }, null, "ip1", "1.1.1.1")
        assertNull(saved.accountId)
        assertEquals("a@b.co", saved.replyEmail)
        assertEquals("page:AbCdEfGhIjK", saved.context)
        assertEquals(InquiryStatus.NEW, saved.status)
        assertEquals(listOf(saved), received)
    }

    @Test
    fun `signed-in inquiry links the account and skips the human check`() {
        humanOk = false
        val saved = service().submit(request(), "acc_1", "ip1", null)
        assertEquals("acc_1", saved.accountId)
    }

    @Test
    fun `anonymous inquiry fails when the human check fails and stores nothing`() {
        humanOk = false
        assertFailsWith<InquiryCaptchaFailedException> { service().submit(request(), null, "ip1", null) }
        assertTrue(repository.list(null, 10).isEmpty())
    }

    @Test
    fun `invalid values are rejected with every violated field`() {
        val violations = assertFailsWith<InquiryInvalidException> {
            service().submit(request("짧아요") { copy(kind = "NOPE", replyEmail = "x", context = "a\nb") }, null, "ip1", null)
        }.data.let { (it as Map<*, *>)["violations"] as List<*> }.map { (it as InquiryViolation).field }
        assertEquals(setOf("kind", "body", "replyEmail", "context"), violations.toSet())
        assertFailsWith<InquiryInvalidException> { service().submit(request("가".repeat(2001)), null, "ip1", null) }
        assertFailsWith<InquiryInvalidException> { service().submit(request("          "), null, "ip1", null) }
    }

    @Test
    fun `anonymous callers are limited per client and signed-in callers per account`() {
        val s = service()
        repeat(2) { s.submit(request(), null, "ip1", null) }
        assertFailsWith<InquiryTooManyException> { s.submit(request(), null, "ip1", null) }
        s.submit(request(), null, "ip2", null) // 다른 클라이언트는 따로
        repeat(3) { s.submit(request(), "acc_1", "ip1", null) }
        assertFailsWith<InquiryTooManyException> { s.submit(request(), "acc_1", "ip1", null) }
    }

    @Test
    fun `the limit window reopens`() {
        val s = service()
        repeat(2) { s.submit(request(), null, "ip1", null) }
        now = now.plus(properties.window).plusSeconds(1)
        s.submit(request(), null, "ip1", null)
    }

    @Test
    fun `a failing listener does not undo the receipt`() {
        val saved = service { error("boom") }.submit(request(), null, "ip1", null)
        assertEquals(saved, repository.find(saved.id))
    }

    @Test
    fun `admin lists newest first, filters by status, marks done and back, and detaching keeps the body`() {
        val s = service()
        val first = s.submit(request("첫 번째 문의입니다 하나"), "acc_1", "ip", null)
        val second = s.submit(request("두 번째 문의입니다 둘"), null, "ip", null)
        val admin = InquiryAdminService(repository, time)
        assertEquals(listOf(second.id, first.id), admin.list(null, 10).map { it.id })
        val done = admin.setStatus(first.id, "DONE")
        assertEquals(now, done.handledAt)
        assertEquals(listOf(second.id), admin.list("NEW", 10).map { it.id })
        assertNull(admin.setStatus(first.id, "NEW").handledAt)
        assertFailsWith<InquiryInvalidException> { admin.setStatus(first.id, "X") }
        assertFailsWith<InquiryInvalidException> { admin.list("X", 10) }
        assertFailsWith<InquiryNotFoundException> { admin.setStatus(999, "DONE") }

        assertEquals(1, repository.detachAccount("acc_1"))
        assertEquals(0, repository.detachAccount("acc_1"))
        repository.find(first.id)!!.let {
            assertNull(it.accountId)
            assertEquals("첫 번째 문의입니다 하나", it.body)
        }
    }
}
