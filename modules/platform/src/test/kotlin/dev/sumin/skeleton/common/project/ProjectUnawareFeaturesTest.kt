package dev.sumin.skeleton.common.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

class ProjectUnawareFeaturesTest {
    private fun registry(vararg extra: Pair<String, String>) = ProjectRegistry.from(
        MockEnvironment().apply {
            setProperty("skeleton.projects.a.hosts", "a.test"); setProperty("skeleton.projects.a.schema", "pa")
            setProperty("skeleton.projects.b.hosts", "b.test"); setProperty("skeleton.projects.b.schema", "pb")
            extra.forEach { (k, v) -> setProperty(k, v) }
        },
    )

    private val present = listOf(ProjectUnawareFeatures.Feature("payment", ProjectUnawareFeaturesTest::class.java.name))
    private val absent = listOf(ProjectUnawareFeatures.Feature("payment", "no.such.Class"))

    @Test
    fun `two active projects and a module that does not know projects yet stop the start - and say which`() {
        val e = assertFailsWith<IllegalStateException> { ProjectUnawareFeatures.check(registry(), present) }
        assertTrue(e.message!!.contains("payment") && e.message!!.contains("skeleton.projects"), e.message)
    }

    @Test
    fun `one active project, a closed one, or no such module on the classpath all pass`() {
        ProjectUnawareFeatures.check(registry("skeleton.projects.b.status" to "closed"), present)
        ProjectUnawareFeatures.check(registry(), absent)
        assertEquals(2, registry().active.size)
    }

    @Test
    fun `the guard table names exactly the modules that still do not know projects`() {
        val labels = ProjectUnawareFeatures.DEFAULT.map { it.label.substringBefore(' ') }.toSet()
        // 프로젝트를 알게 된 모듈(job-queue-jdbc · idempotency · notification · alert · scheduler · async · auth-social · notification-mail · storage · captcha-turnstile · redis · notification-websocket)은 표에 없다
        assertEquals(
            setOf("payment", "crypto", "event-kafka"),
            labels,
        )
    }
}
