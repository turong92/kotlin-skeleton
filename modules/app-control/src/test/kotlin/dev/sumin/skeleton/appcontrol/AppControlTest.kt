package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectDatasource
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 저장 · 짧은 캐시 · 검증 · 운영 기록 - 저장소만 가짜고 나머지는 진짜 */
class AppControlTest {
    private val store = FakeAppControlStore()
    private val time = MutableTime()
    private val audit = RecordingAudit()
    private val control = AppControl(store, audit, time, AppControlProperties(cacheTtl = Duration.ofSeconds(5)), testMapper)
    private val flags = FeatureFlags(control)

    @Test
    fun `a flag nobody set is off, and the caller's default decides`() {
        assertFalse(flags.isEnabled("new-checkout"))
        assertTrue(flags.isEnabled("legacy-path", default = true))
    }

    @Test
    fun `turning a flag on and off shows at once on the instance that changed it`() {
        control.setFlag("acc_op", "new-checkout", enabled = true)
        assertTrue(flags.isEnabled("new-checkout"))

        control.setFlag("acc_op", "new-checkout", enabled = false)
        assertFalse(flags.isEnabled("new-checkout"))
        assertFalse(flags.isEnabled("new-checkout", default = true), "a stored off beats the default")
    }

    @Test
    fun `another instance's change shows after the cache expires and not before`() {
        assertFalse(flags.isEnabled("beta"))   // 캐시에 「없음」이 들어간다
        val other = AppControl(store, RecordingAudit(), time, AppControlProperties(), testMapper)
        other.setFlag("acc_other", "beta", enabled = true)

        time.instant = time.instant.plusSeconds(4)
        assertFalse(flags.isEnabled("beta"), "still inside the ttl")
        time.instant = time.instant.plusSeconds(2)
        assertTrue(flags.isEnabled("beta"), "after the ttl the next read reloads")
    }

    @Test
    fun `reads inside the ttl cost no query`() {
        flags.isEnabled("a")
        flags.isEnabled("b")
        control.snapshot()
        assertEquals(1, store.reads)

        time.instant = time.instant.plusSeconds(6)
        flags.isEnabled("a")
        assertEquals(2, store.reads)
    }

    @Test
    fun `when the store cannot be read the last known values stay, and the next try waits for the ttl`() {
        control.setFlag("acc_op", "beta", enabled = true)
        assertTrue(flags.isEnabled("beta"))
        time.instant = time.instant.plusSeconds(6)
        store.failReads = true
        val before = store.reads

        assertTrue(flags.isEnabled("beta"))
        assertTrue(flags.isEnabled("beta"))
        assertEquals(before + 1, store.reads)

        store.failReads = false
        store.rows.clear()
        time.instant = time.instant.plusSeconds(6)
        assertFalse(flags.isEnabled("beta"))
    }

    @Test
    fun `with nothing known yet and the store down everything is neutral`() {
        store.failReads = true
        assertFalse(flags.isEnabled("beta"))
        assertFalse(control.snapshot().maintenance.enabled)
    }

    @Test
    fun `a change is recorded once with who and what, never the free text`() {
        control.setFlag("acc_op", "new-checkout", enabled = true, clientVisible = true, description = "Checkout v2")
        control.setMaintenance("acc_op", enabled = true, message = "Moving the database", endsAt = null)

        assertEquals(2, audit.actions.size)
        val flag = audit.actions[0]
        assertEquals("acc_op", flag.actorId)
        assertEquals("app-control.flag.updated", flag.action)
        assertEquals("new-checkout", flag.targetId)
        assertEquals(mapOf("enabled" to "true", "clientVisible" to "true"), flag.detail)
        val maintenance = audit.actions[1]
        assertEquals("app-control.maintenance.updated", maintenance.action)
        assertEquals(mapOf("enabled" to "true"), maintenance.detail)
        assertTrue(audit.actions.none { a -> a.detail.values.any { "database" in it || "Checkout" in it } })
    }

    @Test
    fun `deleting a flag removes it and is recorded, deleting a missing one is a 404`() {
        control.setFlag("acc_op", "beta", enabled = true)
        control.deleteFlag("acc_op", "beta")

        assertFalse(flags.isEnabled("beta"))
        assertEquals("app-control.flag.deleted", audit.actions.last().action)
        val missing = assertFailsWith<ApplicationException> { control.deleteFlag("acc_op", "beta") }
        assertEquals(404, missing.status.value())
    }

    @Test
    fun `a bad flag key is refused and nothing is written or recorded`() {
        listOf("", "New-Checkout", "has space", "1abc", "x".repeat(65), "a/b").forEach { key ->
            assertFailsWith<FieldValidationException>(key) { control.setFlag("acc_op", key, enabled = true) }
        }
        assertTrue(store.rows.isEmpty())
        assertTrue(audit.actions.isEmpty())
    }

    @Test
    fun `maintenance on and off, with a message and an end time`() {
        val ends = Instant.parse("2026-11-16T10:00:00Z")
        control.setMaintenance("acc_op", enabled = true, message = "Back at 10", endsAt = ends)
        val on = control.snapshot().maintenance
        assertTrue(on.enabled)
        assertEquals("Back at 10", on.message)
        assertEquals(ends, on.endsAt)

        control.setMaintenance("acc_op", enabled = false, message = null, endsAt = null)
        assertFalse(control.snapshot().maintenance.enabled)
    }

    @Test
    fun `a message that is too long or an end time in the past is refused`() {
        assertFailsWith<FieldValidationException> { control.setMaintenance("acc_op", true, "x".repeat(501), null) }
        assertFailsWith<FieldValidationException> { control.setMaintenance("acc_op", true, null, time.instant.minusSeconds(1)) }
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `platform versions are stored per platform and compared as numbers`() {
        control.setPlatform("acc_op", "ios", minVersion = "1.9", recommendedVersion = "1.10", storeUrl = "https://apps.apple.com/app/id1")

        val ios = control.snapshot().platforms.getValue("ios")
        assertEquals("1.9", ios.minVersion)
        assertEquals("1.10", ios.recommendedVersion)
        assertEquals("https://apps.apple.com/app/id1", ios.storeUrl)
        assertNull(control.snapshot().platforms["android"])
    }

    @Test
    fun `a bad version, a recommended one below the minimum, a non-https store link or a bad platform name is refused`() {
        assertFailsWith<FieldValidationException> { control.setPlatform("acc_op", "ios", "v1", null, null) }
        assertFailsWith<FieldValidationException> { control.setPlatform("acc_op", "ios", "1.10", "1.9", null) }
        assertFailsWith<FieldValidationException> { control.setPlatform("acc_op", "ios", "1.0", null, "http://example.com/app") }
        assertFailsWith<FieldValidationException> { control.setPlatform("acc_op", "iOS", "1.0", null, null) }
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `setting a platform with nothing in it removes it`() {
        control.setPlatform("acc_op", "android", "2.0", null, null)
        control.setPlatform("acc_op", "android", null, null, null)

        assertNull(control.snapshot().platforms["android"])
        assertEquals("app-control.platform.updated", audit.actions.last().action)
    }

    @Test
    fun `projects served by one process keep their own values and their own cache`() {
        // 프로젝트마다 DB 스키마가 따로라 저장소는 요청의 프로젝트가 고른다 - 캐시도 프로젝트마다여야 한다
        val stores = mapOf("a" to FakeAppControlStore(), "b" to FakeAppControlStore())
        val routed = object : AppControlStore {
            private fun current() = stores.getValue(ProjectContext.current().id)
            override fun <T> transaction(block: () -> T): T = current().transaction(block)
            override fun all() = current().all()
            override fun put(entry: AppControlEntry) = current().put(entry)
            override fun delete(name: String) = current().delete(name)
        }
        val shared = AppControl(routed, audit, time, AppControlProperties(), testMapper)
        fun project(id: String) = Project(id, listOf("$id.example.com"), id, ProjectDatasource(), emptyMap())

        ProjectContext.runIn(project("a")) { shared.setFlag("acc_op", "beta", enabled = true) }

        assertTrue(ProjectContext.runIn(project("a")) { FeatureFlags(shared).isEnabled("beta") })
        assertFalse(ProjectContext.runIn(project("b")) { FeatureFlags(shared).isEnabled("beta") })
        ProjectContext.runIn(project("b")) { shared.setMaintenance("acc_op", enabled = true, message = "B only", endsAt = null) }
        assertFalse(ProjectContext.runIn(project("a")) { shared.snapshot().maintenance.enabled })
        assertTrue(ProjectContext.runIn(project("b")) { shared.snapshot().maintenance.enabled })
    }

    @Test
    fun `a row that cannot be read is skipped and the others still load`() {
        store.put(AppControlEntry("flag:broken", "{not json", "acc_x", time.instant))
        control.setFlag("acc_op", "beta", enabled = true)

        assertTrue(flags.isEnabled("beta"))
    }
}
