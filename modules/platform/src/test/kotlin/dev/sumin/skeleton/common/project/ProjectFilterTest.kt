package dev.sumin.skeleton.common.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper

class ProjectFilterTest {
    private val registry = ProjectRegistry.from(
        MockEnvironment()
            .withProperty("skeleton.projects.a.hosts", "a.localhost").withProperty("skeleton.projects.a.schema", "pa")
            .withProperty("skeleton.projects.b.hosts", "b.localhost").withProperty("skeleton.projects.b.schema", "pb")
            .withProperty("skeleton.projects.old.hosts", "old.localhost").withProperty("skeleton.projects.old.status", "closed"),
    )
    private val filter = ProjectFilter(registry, ObjectMapper())

    private fun call(host: String, uri: String = "/api/v1/x"): Pair<MockHttpServletResponse, String?> {
        var seen: String? = "unset"
        val req = MockHttpServletRequest("GET", uri).apply { serverName = host }
        val res = MockHttpServletResponse()
        filter.doFilter(req, res) { _, _ -> seen = ProjectContext.currentOrNull()?.id }
        return res to seen
    }

    @Test
    fun `the project is the one whose host the request came to - and only for that request`() {
        assertEquals("a", call("a.localhost").second)
        assertEquals("b", call("B.localhost").second)
        assertNull(ProjectContext.currentOrNull())
    }

    @Test
    fun `an unknown host is refused with 421 and never reaches the app`() {
        val (res, seen) = call("evil.example")
        assertEquals(421, res.status)
        assertEquals("unset", seen)
        assertTrue(res.contentAsString.contains("COMMON.UNKNOWN_HOST"), res.contentAsString)
    }

    @Test
    fun `the health probe passes without a project - the container asks by localhost`() {
        val (res, seen) = call("localhost", "/health")
        assertEquals(200, res.status)
        assertNull(seen)
    }

    @Test
    fun `a closed project answers 503 service closed - not the 421 of an unknown host - and the app never runs for it`() {
        val (res, seen) = call("old.localhost")
        assertEquals(503, res.status)
        assertEquals("unset", seen)
        assertTrue(res.contentAsString.contains("COMMON.PROJECT_CLOSED"), res.contentAsString)
        assertEquals(listOf("a", "b"), registry.active.map { it.id }, "a closed project is not counted, gets no pool and is not migrated")
    }
}
