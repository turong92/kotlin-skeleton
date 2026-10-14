package dev.sumin.skeleton.common.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProjectContextTest {
    private fun project(id: String) = Project(id, listOf("$id.test"), id, ProjectDatasource(), emptyMap())

    @Test
    fun `outside a project the context is empty and current fails loudly`() {
        assertNull(ProjectContext.currentOrNull())
        val e = assertFailsWith<NoProjectContextException> { ProjectContext.current() }
        assertEquals(true, e.message!!.contains("runIn"), e.message)
    }

    @Test
    fun `runIn sets the project for the block and puts the previous one back - even when the block throws`() {
        val a = project("a")
        val b = project("b")
        ProjectContext.runIn(a) {
            assertSame(a, ProjectContext.current())
            ProjectContext.runIn(b) { assertSame(b, ProjectContext.current()) }
            assertSame(a, ProjectContext.current())
            runCatching { ProjectContext.runIn(b) { error("boom") } }
            assertSame(a, ProjectContext.current())
        }
        assertNull(ProjectContext.currentOrNull())
    }

    @Test
    fun `scopedKey leaves a key alone outside a project and prefixes the project inside one`() {
        assertEquals("ip:1.2.3.4", ProjectContext.scopedKey("ip:1.2.3.4"))
        val inA = ProjectContext.runIn(project("a")) { ProjectContext.scopedKey("ip:1.2.3.4") }
        val inB = ProjectContext.runIn(project("b")) { ProjectContext.scopedKey("ip:1.2.3.4") }
        assertEquals(false, inA == inB)
        assertEquals(true, inA.endsWith("ip:1.2.3.4") && inA.startsWith("a"), inA)
    }

    @Test
    fun `carry runs a task in the project of whoever handed it over, on any thread`() {
        val seen = java.util.concurrent.atomic.AtomicReference<String?>("unset")
        val task = ProjectContext.runIn(project("a")) { ProjectContext.carry(Runnable { seen.set(ProjectContext.currentOrNull()?.id) }) }
        Thread(task).apply { start(); join() }
        assertEquals("a", seen.get())
        assertNull(ProjectContext.currentOrNull())
    }

    @Test
    fun `carry outside a project hands the task back untouched`() {
        val task = Runnable { }
        assertSame(task, ProjectContext.carry(task))
    }
}
