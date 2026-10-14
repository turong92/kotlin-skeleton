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
}
