package dev.sumin.skeleton.app.sample.cheer

import kotlin.test.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.put

/**
 * 「회수 즉시」를 조립된 앱에서 — 진짜 보안 체인 · 진짜 `AccountOperatorAccess`(저장소의 계정을 매 요청 읽는다)로, 토큰은 그대로 쥔 채
 * 역할을 주고 · 거두고 · 운영자를 정지한다. 모듈별 시험은 교체 가능한 대역을 쓰므로 이 시험이 마지막 연결을 본다.
 */
@TestPropertySource(properties = [
    "skeleton.authz.roles.ADMIN=*",
    "skeleton.authz.roles.MODERATOR=board.moderate,board.manage",
    "skeleton.authz.roles.CS=payment.read,payment.refund,payment.operate",
])
class OperatorRevocationIntegrationTest : CheerIntegrationBase() {
    private fun grant(account: String, role: String) = jdbc.sql("insert into account_roles (account_id, role) values (:a, :r)").param("a", account).param("r", role).update()
    private fun revoke(account: String, role: String) = jdbc.sql("delete from account_roles where account_id = :a and role = :r").param("a", account).param("r", role).update()

    @Test
    fun `a role granted to a user opens the payment operator API with the same token and closing it again is immediate`() {
        val account = accountOf("user@example.com")
        get("$ADMIN/orders", user).andExpect { status { isForbidden() } }
        grant(account, "CS")
        try {
            get("$ADMIN/orders", user).andExpect { status { isOk() } }
        } finally {
            revoke(account, "CS")
        }
        get("$ADMIN/orders", user).andExpect { status { isForbidden() } }
    }

    @Test
    fun `taking the MODERATOR role away closes the board moderation on the next call`() {
        val account = accountOf("moderator@example.com")
        fun hide() = mvc.put("/api/v1/boards/general/posts/987654/moderation") {
            header("Authorization", "Bearer $stranger"); contentType = org.springframework.http.MediaType.APPLICATION_JSON; content = """{"status":"HIDDEN"}"""
        }
        hide().andExpect { status { isNotFound() } }   // 권한은 통과 — 그 글이 없을 뿐
        revoke(account, "MODERATOR")
        try {
            hide().andExpect { status { isForbidden() } }
        } finally {
            grant(account, "MODERATOR")
        }
        hide().andExpect { status { isNotFound() } }
    }

    @Test
    fun `a suspended operator is refused by the legal admin API with the token still in hand`() {
        val account = accountOf("admin@example.com")
        get("/api/v1/legal/admin/documents", operator).andExpect { status { isOk() } }
        jdbc.sql("update accounts set status = 'SUSPENDED' where id = :a").param("a", account).update()
        try {
            get("/api/v1/legal/admin/documents", operator).andExpect { status { isForbidden() } }
        } finally {
            jdbc.sql("update accounts set status = 'ACTIVE' where id = :a").param("a", account).update()
        }
        get("/api/v1/legal/admin/documents", operator).andExpect { status { isOk() } }
    }
}
