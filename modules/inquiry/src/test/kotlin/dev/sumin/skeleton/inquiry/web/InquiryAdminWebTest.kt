package dev.sumin.skeleton.inquiry.web

import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.inquiry.InMemoryInquiryRepository
import dev.sumin.skeleton.inquiry.InquiryAdminService
import dev.sumin.skeleton.inquiry.InquiryKind
import dev.sumin.skeleton.inquiry.InquiryPermissions
import dev.sumin.skeleton.inquiry.NewInquiry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 운영자 경로 — 익명 401 · 권한 없음 403 · 읽기와 처리 표시가 다른 권한 · 응답에 계정 id 가 없다 · 처리 표시만 운영 기록에 남는다 */
class InquiryAdminWebTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val repository = InMemoryInquiryRepository()
    private val permissions = mapOf("reader" to setOf(InquiryPermissions.READ), "handler" to setOf(InquiryPermissions.READ, InquiryPermissions.HANDLE))
    private val audits = mutableListOf<OperatorAction>()
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
        InquiryAdminController(
            InquiryAdminService(repository, TimeProvider.fixed(now)),
            object : OperatorAccess { override fun permissionsOf(ref: OperatorRef) = permissions[ref.accountId].orEmpty() },
            OperatorAuditLog { audits += it },
        ),
    ).setControllerAdvice(GlobalExceptionHandler()).build()

    private fun who(name: String) = TestingAuthenticationToken(name, "n", "ROLE_USER").apply { isAuthenticated = true }

    private fun seed(body: String, account: String? = null) =
        repository.create(NewInquiry(InquiryKind.BUG, body, "a@b.co", account, "page:x", "ko", now)).id

    @Test
    fun `anonymous is 401, a caller without the permission is 403`() {
        mvc.perform(get("/api/v1/admin/inquiries")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/admin/inquiries").principal(AnonymousAuthenticationToken("k", "anon", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/admin/inquiries").principal(who("nobody"))).andExpect(status().isForbidden)
    }

    @Test
    fun `a reader lists newest first with a status filter and sees no account id`() {
        val first = seed("첫 번째 문의입니다 하나", "acc_secret")
        val second = seed("두 번째 문의입니다 둘")
        mvc.perform(get("/api/v1/admin/inquiries").principal(who("reader")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].id").value(second))
            .andExpect(jsonPath("$.values[1].id").value(first))
            .andExpect(jsonPath("$.values[1].signedIn").value(true))
            .andExpect(jsonPath("$.values[0].signedIn").value(false))
            .andExpect(jsonPath("$.values[1].accountId").doesNotExist())
        mvc.perform(get("/api/v1/admin/inquiries?status=DONE").principal(who("reader"))).andExpect(jsonPath("$.values").isEmpty)
        mvc.perform(get("/api/v1/admin/inquiries?status=X").principal(who("reader"))).andExpect(status().isBadRequest)
    }

    @Test
    fun `marking done needs the handle permission, stamps the time and is audited`() {
        val id = seed("처리할 문의입니다 하나")
        val body = """{"status":"DONE"}"""
        mvc.perform(put("/api/v1/admin/inquiries/$id/status").principal(who("reader")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        assertEquals(emptyList(), audits)

        mvc.perform(put("/api/v1/admin/inquiries/$id/status").principal(who("handler")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("DONE")).andExpect(jsonPath("$.value.handledAt").value("2026-11-01T00:00:00Z"))
        assertEquals(listOf(OperatorAction("handler", "inquiry.status.changed", "inquiry", id.toString(), mapOf("status" to "DONE"))), audits)

        mvc.perform(put("/api/v1/admin/inquiries/999/status").principal(who("handler")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound)
        mvc.perform(put("/api/v1/admin/inquiries/$id/status").principal(who("handler")).contentType(MediaType.APPLICATION_JSON).content("""{"status":"X"}""")).andExpect(status().isBadRequest)
    }
}
