package dev.sumin.skeleton.inquiry.jdbc

import dev.sumin.skeleton.inquiry.InquiryKind
import dev.sumin.skeleton.inquiry.InquiryRepository
import dev.sumin.skeleton.inquiry.InquiryStatus
import dev.sumin.skeleton.inquiry.NewInquiry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** 모듈 마이그레이션(Flyway)을 깔고 두 방언에서 저장 · 목록 · 처리 표시 · 계정 끊기가 같은 뜻인지 - JDBC 저장소가 메모리 대신 서는 것도 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [InquiryJdbcTestApplication::class])
class InquiryRepositoryDbTest {
    @Autowired lateinit var repository: InquiryRepository

    private val at = Instant.parse("2026-11-01T00:00:00.123456Z")

    private fun new(body: String, account: String? = null, at: Instant = this.at) =
        NewInquiry(InquiryKind.FEATURE, body, "a@b.co", account, "screen:home", "ko", at)

    @Test
    fun `the jdbc repository replaces the in-memory fallback`() {
        assertIs<JdbcInquiryRepository>(repository)
    }

    @Test
    fun `stores and reads back every column`() {
        val saved = repository.create(new("저장 시험 문의입니다", "acc_1"))
        assertEquals(saved, repository.find(saved.id))
        assertEquals(InquiryKind.FEATURE, saved.kind)
        assertEquals(InquiryStatus.NEW, saved.status)
        assertEquals("acc_1", saved.accountId)
        assertEquals("screen:home", saved.context)
        assertEquals(at, saved.createdAt)
        assertNull(saved.handledAt)
    }

    @Test
    fun `lists newest first with a status filter`() {
        val older = repository.create(new("오래된 문의 시험입니다", at = at.minusSeconds(60)))
        val newer = repository.create(new("새 문의 시험입니다", at = at))
        repository.setStatus(older.id, InquiryStatus.DONE, at)
        val all = repository.list(null, 100).filter { it.id in setOf(older.id, newer.id) }
        assertEquals(listOf(newer.id, older.id), all.map { it.id })
        assertEquals(true, repository.list(InquiryStatus.DONE, 100).any { it.id == older.id })
        assertEquals(false, repository.list(InquiryStatus.NEW, 100).any { it.id == older.id })
    }

    @Test
    fun `done stamps the handled time and new clears it`() {
        val saved = repository.create(new("처리 시험 문의입니다"))
        val done = repository.setStatus(saved.id, InquiryStatus.DONE, at.plusSeconds(5))!!
        assertEquals(at.plusSeconds(5), done.handledAt)
        assertNull(repository.setStatus(saved.id, InquiryStatus.NEW, at)!!.handledAt)
        assertNull(repository.setStatus(-1, InquiryStatus.DONE, at))
    }

    @Test
    fun `detaching an account keeps the row and is idempotent`() {
        val acc = "acc_detach_${System.nanoTime()}"
        val saved = repository.create(new("계정 끊기 시험 문의입니다", acc))
        assertEquals(1, repository.detachAccount(acc))
        assertEquals(0, repository.detachAccount(acc))
        assertNull(repository.find(saved.id)!!.accountId)
        assertEquals("계정 끊기 시험 문의입니다", repository.find(saved.id)!!.body)
    }
}
