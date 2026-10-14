package dev.sumin.skeleton.auth.jwt

import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectDatasource
import dev.sumin.skeleton.common.project.ProjectScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 프로젝트가 있을 때 — 한 프로젝트가 낸 토큰은 다른 프로젝트에서 거절된다 (키가 같아도) */
class JwtTokenServiceProjectsTest {
    private fun project(id: String) = Project(id, listOf("$id.test"), id, ProjectDatasource(), emptyMap())
    private val a = project("a")
    private val b = project("b")
    private val principal = CurrentPrincipal(accountId = "acc_1", email = "x@example.com", roles = setOf("USER"))

    private fun service(secretOf: (Project) -> String) =
        JwtTokenService.perProject(ProjectScoped { AuthProperties.Jwt(issuer = "iss-${it.id}", secret = secretOf(it)) })

    @Test
    fun `a token from project A is rejected in project B even when both share key and issuer`() {
        val shared = JwtTokenService.perProject(ProjectScoped { AuthProperties.Jwt(issuer = "same", secret = "shared-secret-shared-secret-32-bytes!!") })
        val token = ProjectContext.runIn(a) { shared.issue(principal).accessToken }
        assertEquals("acc_1", ProjectContext.runIn(a) { shared.authenticate(token) }?.accountId)
        assertNull(ProjectContext.runIn(b) { shared.authenticate(token) })
    }

    @Test
    fun `each project signs with its own key and issuer`() {
        val s = service { "secret-of-${it.id}-secret-of-${it.id}-32-bytes!!" }
        val tokenA = ProjectContext.runIn(a) { s.issue(principal).accessToken }
        assertNull(ProjectContext.runIn(b) { s.authenticate(tokenA) })
        assertNotNull(ProjectContext.runIn(a) { s.authenticate(tokenA) })
    }

    @Test
    fun `without a project in context a scoped service fails instead of picking one`() {
        val s = service { "secret-of-${it.id}-secret-of-${it.id}-32-bytes!!" }
        assertFailsWith<dev.sumin.skeleton.common.project.NoProjectContextException> { s.authenticate("x.y.z") }
    }
}
