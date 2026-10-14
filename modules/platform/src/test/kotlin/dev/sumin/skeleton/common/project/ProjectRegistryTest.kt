package dev.sumin.skeleton.common.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.mock.env.MockEnvironment

class ProjectRegistryTest {
    private fun env(vararg pairs: Pair<String, String>) = MockEnvironment().also { e -> pairs.forEach { (k, v) -> e.setProperty(k, v) } }

    private val twoProjects = arrayOf(
        "skeleton.projects.a.hosts" to "a.localhost, www.a.localhost",
        "skeleton.projects.a.schema" to "proj_a",
        "skeleton.projects.b.hosts" to "b.localhost",
        "skeleton.projects.b.schema" to "proj_b",
    )

    @Test
    fun `an empty or missing list means no projects - the classic single-service app`() {
        assertTrue(ProjectRegistry.configured(env()).not())
    }

    @Test
    fun `projects are found by host - case and surrounding space do not matter - and an unknown host is not a project`() {
        val registry = ProjectRegistry.from(env(*twoProjects))
        assertEquals(listOf("a", "b"), registry.projects.map { it.id })
        assertEquals("a", registry.byHost("WWW.A.localhost")?.id)
        assertEquals("b", registry.byHost("b.localhost")?.id)
        assertNull(registry.byHost("c.localhost"))
        assertNull(registry.byHost(null))
    }

    @Test
    fun `two projects on one host fail the start`() {
        val e = assertFailsWith<IllegalStateException> {
            ProjectRegistry.from(env("skeleton.projects.a.hosts" to "x.test", "skeleton.projects.a.schema" to "pa", "skeleton.projects.b.hosts" to "X.test", "skeleton.projects.b.schema" to "pb"))
        }
        assertTrue(e.message!!.contains("x.test") && e.message!!.contains("'a'") && e.message!!.contains("'b'"), e.message)
    }

    @Test
    fun `two projects on one schema fail the start - the data of one must never be the other's`() {
        val e = assertFailsWith<IllegalStateException> {
            ProjectRegistry.from(env("skeleton.projects.a.hosts" to "a.test", "skeleton.projects.a.schema" to "shared", "skeleton.projects.b.hosts" to "b.test", "skeleton.projects.b.schema" to "SHARED"))
        }
        assertTrue(e.message!!.contains("shared"), e.message)
    }

    @Test
    fun `a project without a host, without a schema, or with a schema that is not a plain identifier fails the start`() {
        assertFailsWith<IllegalStateException> { ProjectRegistry.from(env("skeleton.projects.a.schema" to "pa")) }
        assertFailsWith<IllegalStateException> { ProjectRegistry.from(env("skeleton.projects.a.hosts" to "a.test")) }
        assertFailsWith<IllegalStateException> { ProjectRegistry.from(env("skeleton.projects.a.hosts" to "a.test", "skeleton.projects.a.schema" to "pa; drop schema public")) }
        assertFailsWith<IllegalStateException> { ProjectRegistry.from(env("skeleton.projects.a.hosts" to "a.test:8080", "skeleton.projects.a.schema" to "pa")) }
    }

    @Test
    fun `datasource overrides and the pool maximum are read - the pool maximum defaults small`() {
        val registry = ProjectRegistry.from(
            env(*twoProjects, "skeleton.projects.a.datasource.username" to "proj_a_user", "skeleton.projects.a.datasource.password" to "pw", "skeleton.projects.a.datasource.pool-max" to "7"),
        )
        val a = registry.projects.first { it.id == "a" }
        assertEquals("proj_a_user", a.datasource.username)
        assertEquals(7, a.datasource.poolMax)
        assertEquals(3, registry.projects.first { it.id == "b" }.datasource.poolMax)
        assertNull(registry.projects.first { it.id == "b" }.datasource.username)
    }

    @ConfigurationProperties("skeleton.demo")
    data class Demo(val secret: String = "common-secret", val label: String = "common-label", val inner: Inner = Inner()) {
        data class Inner(val ttl: Int = 15, val flag: Boolean = false)
    }

    @Test
    fun `a project config is laid over the common skeleton values and bound into the same properties class`() {
        val registry = ProjectRegistry.from(
            env(
                *twoProjects,
                "skeleton.demo.label" to "from-common",
                "skeleton.projects.a.config.demo.secret" to "secret-of-a",
                "skeleton.projects.a.config.demo.inner.ttl" to "99",
            ),
        )
        val a = registry.bind(registry.projects.first { it.id == "a" }, "skeleton.demo", Demo::class.java)
        val b = registry.bind(registry.projects.first { it.id == "b" }, "skeleton.demo", Demo::class.java)
        assertEquals(Demo("secret-of-a", "from-common", Demo.Inner(ttl = 99)), a)
        assertEquals(Demo("common-secret", "from-common", Demo.Inner()), b)
    }

    @Test
    fun `a project config value may use a placeholder`() {
        val registry = ProjectRegistry.from(env(*twoProjects, "OVATION_SECRET" to "from-env", "skeleton.projects.a.config.demo.secret" to "\${OVATION_SECRET}"))
        assertEquals("from-env", registry.bind(registry.projects.first { it.id == "a" }, "skeleton.demo", Demo::class.java).secret)
    }

    @Test
    fun `scoped parts are built once per project on first use and need a project in context`() {
        val registry = ProjectRegistry.from(env(*twoProjects))
        var built = 0
        val scoped = ProjectScoped { project -> built++; "part-of-${project.id}" }
        assertFailsWith<NoProjectContextException> { scoped.get() }
        val a = registry.projects.first { it.id == "a" }
        val b = registry.projects.first { it.id == "b" }
        assertEquals("part-of-a", ProjectContext.runIn(a) { scoped.get() })
        assertSame(ProjectContext.runIn(a) { scoped.get() }, ProjectContext.runIn(a) { scoped.get() })
        assertEquals("part-of-b", ProjectContext.runIn(b) { scoped.get() })
        assertEquals(2, built)
    }
}
