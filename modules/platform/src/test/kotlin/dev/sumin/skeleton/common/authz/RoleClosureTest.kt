package dev.sumin.skeleton.common.authz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoleClosureTest {
    private val graph = mapOf("STAFF" to setOf("CS"), "CS" to setOf("VIEWER"))

    @Test
    fun `a role expands to itself and everything it inherits, transitively`() {
        assertEquals(setOf("STAFF", "CS", "VIEWER"), RoleClosure.expand(setOf("STAFF"), { graph[it].orEmpty() }))
        assertEquals(setOf("CS", "VIEWER"), RoleClosure.expand(setOf("CS"), { graph[it].orEmpty() }))
    }

    @Test
    fun `a cycle in the table does not hang the expansion`() {
        val loop = mapOf("A" to setOf("B"), "B" to setOf("A"))
        assertEquals(setOf("A", "B"), RoleClosure.expand(setOf("A"), { loop[it].orEmpty() }))
    }

    @Test
    fun `the expansion stops at the depth limit`() {
        val chain = (0..9).associate { "R$it" to setOf("R${it + 1}") }
        assertEquals((0..8).map { "R$it" }.toSet(), RoleClosure.expand(setOf("R0"), { chain[it].orEmpty() }))
    }

    @Test
    fun `cycleOf names the loop from its first role and is null without one`() {
        assertEquals(listOf("A", "B", "A"), RoleClosure.cycleOf(mapOf("A" to setOf("B"), "B" to setOf("A"))))
        assertEquals(listOf("A", "A"), RoleClosure.cycleOf(mapOf("A" to setOf("A"))))
        assertEquals(listOf("B", "C", "B"), RoleClosure.cycleOf(mapOf("A" to setOf("B"), "B" to setOf("C"), "C" to setOf("B"))))
        assertNull(RoleClosure.cycleOf(graph))
        assertNull(RoleClosure.cycleOf(mapOf("A" to setOf("B", "C"), "B" to setOf("C"))))   // 다이아몬드는 순환이 아니다
    }
}
