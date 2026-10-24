package dev.sumin.skeleton.app.sample.cheer

import kotlin.test.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post

/**
 * 조립된 앱에서 authz 의 「쓰는 계정이 있는 역할 삭제 막기」 — 진짜 `AccountRoleHolders`(계정 표를 센다)가 `RoleHolders` 로 꽂혀
 * 계정에 준 역할은 409 `AUTHZ.ROLE_IN_USE`, 거두면 204 로 지워진다.
 */
@TestPropertySource(properties = ["skeleton.authz.roles.ADMIN=*", "skeleton.authz.roles.MODERATOR=board.moderate,board.manage"])
class RoleAdminHoldersIntegrationTest : CheerIntegrationBase() {
    @Test
    fun `a role an account holds cannot be deleted until it is taken away`() {
        val account = accountOf("user@example.com")
        mvc.post("/api/v1/admin/roles") {
            header("Authorization", "Bearer $operator"); contentType = MediaType.APPLICATION_JSON
            content = """{"name":"HOLDER_TEST","description":"x","permissions":["payment.read"]}"""
        }.andExpect { status { isCreated() } }
        jdbc.sql("insert into account_roles (account_id, role) values (:a, 'HOLDER_TEST')").param("a", account).update()

        mvc.delete("/api/v1/admin/roles/HOLDER_TEST") { header("Authorization", "Bearer $operator") }
            .andExpect { status { isConflict() }; jsonPath("$.code") { value("AUTHZ.ROLE_IN_USE") } }

        jdbc.sql("delete from account_roles where account_id = :a and role = 'HOLDER_TEST'").param("a", account).update()
        mvc.delete("/api/v1/admin/roles/HOLDER_TEST") { header("Authorization", "Bearer $operator") }
            .andExpect { status { isNoContent() } }
    }

    @Test
    fun `deleting a role takes it from suspended accounts too so a re-created name does not hand it back`() {
        val account = accountOf("user@example.com")
        mvc.post("/api/v1/admin/roles") {
            header("Authorization", "Bearer $operator"); contentType = MediaType.APPLICATION_JSON
            content = """{"name":"GHOST_ROLE","description":"x","permissions":["payment.read"]}"""
        }.andExpect { status { isCreated() } }
        jdbc.sql("insert into account_roles (account_id, role) values (:a, 'GHOST_ROLE')").param("a", account).update()
        jdbc.sql("update accounts set status = 'SUSPENDED' where id = :a").param("a", account).update()
        try {
            mvc.delete("/api/v1/admin/roles/GHOST_ROLE") { header("Authorization", "Bearer $operator") }.andExpect { status { isNoContent() } }
            kotlin.test.assertEquals(0L, jdbc.sql("select count(*) from account_roles where role = 'GHOST_ROLE'").query(Long::class.java).single())

            mvc.post("/api/v1/admin/roles") {
                header("Authorization", "Bearer $operator"); contentType = MediaType.APPLICATION_JSON
                content = """{"name":"GHOST_ROLE","description":"again","permissions":["payment.read"]}"""
            }.andExpect { status { isCreated() } }
            jdbc.sql("update accounts set status = 'ACTIVE' where id = :a").param("a", account).update()
            get("$ADMIN/orders", user).andExpect { status { isForbidden() } }   // 아무도 준 적 없는 권한
        } finally {
            jdbc.sql("update accounts set status = 'ACTIVE' where id = :a").param("a", account).update()
            jdbc.sql("delete from account_roles where role = 'GHOST_ROLE'").update()
            jdbc.sql("delete from authz_roles where name = 'GHOST_ROLE'").update()
        }
    }
}
