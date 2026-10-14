package dev.sumin.skeleton.common.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** 프로젝트 모듈이 한 번 만드는 표시 — 컨트롤러에는 이것만 붙인다 */
@ProjectApi("a")
annotation class AApi

class ProjectApiTest {
    @RestController @AApi
    class AHello { @GetMapping("/api/v1/hello") fun hello() = "hello from a" }

    @RestController @ProjectApi("b")
    class BHello { @GetMapping("/api/v1/hello") fun hello() = "hello from b" }

    @RestController
    class Shared { @GetMapping("/api/v1/shared") fun shared() = "shared" }

    @RestController @ProjectApi("not-in-this-server")
    class Elsewhere { @GetMapping("/api/v1/elsewhere") fun elsewhere() = "elsewhere" }

    private val env = MockEnvironment()
        .withProperty("skeleton.projects.a.hosts", "a.test").withProperty("skeleton.projects.a.schema", "pa")
        .withProperty("skeleton.projects.b.hosts", "b.test").withProperty("skeleton.projects.b.schema", "pb")
    private val registry = ProjectRegistry.from(env)

    private fun mvc(withProjects: Boolean): MockMvc = MockMvcBuilders.standaloneSetup(AHello(), BHello(), Shared(), Elsewhere())
        .apply { if (withProjects) setCustomHandlerMapping { ProjectApiHandlerMapping(registry) } }
        .build()

    private fun get(mvc: MockMvc, projectId: String?, path: String): Pair<Int, String> {
        val call = { val r = mvc.get(path).andReturn().response; r.status to r.contentAsString }
        return if (projectId == null) call() else ProjectContext.runIn(registry.projects.first { it.id == projectId }, call)
    }

    @Test
    fun `two projects may serve the same path - each domain gets its own controller and the other's is a 404`() {
        val mvc = mvc(true)
        assertEquals(200 to "hello from a", get(mvc, "a", "/api/v1/hello"))
        assertEquals(200 to "hello from b", get(mvc, "b", "/api/v1/hello"))
    }

    @Test
    fun `a controller without a mark opens on every domain`() {
        val mvc = mvc(true)
        assertEquals(200 to "shared", get(mvc, "a", "/api/v1/shared"))
        assertEquals(200 to "shared", get(mvc, "b", "/api/v1/shared"))
    }

    @Test
    fun `a controller marked with a project this server does not list opens on no domain`() {
        val mvc = mvc(true)
        assertEquals(404, get(mvc, "a", "/api/v1/elsewhere").first)
        assertEquals(404, get(mvc, "b", "/api/v1/elsewhere").first)
    }

    @Test
    fun `with no project list the marks are ignored - one service opens everything it contains`() {
        // 같은 경로를 두 컨트롤러가 가지면 표시를 무시하는 한 서비스에서는 모호하다 — 프로젝트 하나를 떼어 띄울 때는 그 프로젝트의 컨트롤러만 빌드에 들어 있다
        assertFailsWith<Exception> { mvc(false) }
        val single = MockMvcBuilders.standaloneSetup(AHello(), Shared(), Elsewhere()).build()
        assertEquals(200 to "hello from a", get(single, null, "/api/v1/hello"))
        assertEquals(200 to "elsewhere", get(single, null, "/api/v1/elsewhere"))
    }
}
