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
    fun `deleting a platform removes it and is recorded, a missing one is a 404`() {
        control.setPlatform("acc_op", "android", "2.0", null, "https://play.google.com/store/apps/details?id=x")
        control.deletePlatform("acc_op", "android")

        assertNull(control.snapshot().platforms["android"])
        assertEquals("app-control.platform.deleted", audit.actions.last().action)
        assertEquals(404, assertFailsWith<ApplicationException> { control.deletePlatform("acc_op", "android") }.status.value())
    }

    @Test
    fun `a mobile platform with a minimum needs the store link, the web does not`() {
        assertFailsWith<FieldValidationException> { control.setPlatform("acc_op", "ios", "2.0", null, null) }
        assertFailsWith<FieldValidationException> { control.setPlatform("acc_op", "android", "2.0", "2.1", null) }
        control.setPlatform("acc_op", "web", "2026.1", null, null)

        assertEquals("2026.1", control.snapshot().platforms.getValue("web").minVersion)
    }

    @Test
    fun `patching a platform changes only the given parts and keeps the rest, put replaces it whole`() {
        control.setPlatform("acc_op", "ios", "1.9", "1.10", "https://apps.apple.com/app/id1")

        control.patchPlatform("acc_op", "ios", minVersion = null, recommendedVersion = "1.11", storeUrl = null)
        val patched = control.snapshot().platforms.getValue("ios")
        assertEquals("1.9", patched.minVersion)
        assertEquals("1.11", patched.recommendedVersion)
        assertEquals("https://apps.apple.com/app/id1", patched.storeUrl)

        control.setPlatform("acc_op", "ios", "2.0", null, "https://apps.apple.com/app/id1")
        assertNull(control.snapshot().platforms.getValue("ios").recommendedVersion)
    }

    @Test
    fun `a patch is checked against what it makes - below the stored minimum, or a first minimum without a link`() {
        control.setPlatform("acc_op", "ios", "1.9", null, "https://apps.apple.com/app/id1")
        assertFailsWith<FieldValidationException> { control.patchPlatform("acc_op", "ios", null, "1.5", null) }
        assertFailsWith<FieldValidationException> { control.patchPlatform("acc_op", "android", "2.0", null, null) }
        assertNull(control.snapshot().platforms["android"])
    }

    @Test
    fun `a platform record says whether the store link changed, not the link itself`() {
        control.setPlatform("acc_op", "ios", "1.9", null, "https://apps.apple.com/app/id1")

        assertEquals(mapOf("minVersion" to "1.9", "storeUrlSet" to "true"), audit.actions.last().detail)
    }

    @Test
    fun `a slow read that fails does not queue the others, they get the last values at once`() {
        control.setFlag("acc_op", "beta", enabled = true)
        assertTrue(flags.isEnabled("beta"))
        time.instant = time.instant.plusSeconds(6)
        val inRead = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        store.failReads = true
        store.duringRead = { inRead.countDown(); release.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        val slow = Thread { flags.isEnabled("beta") }.also { it.start() }
        assertTrue(inRead.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val answered = java.util.concurrent.CompletableFuture.supplyAsync { flags.isEnabled("beta") }.get(5, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(answered, "the others do not wait for the slow read")
        release.countDown()
        slow.join(10_000)
    }

    @Test
    fun `the cache lifetime counts from the end of the read, so a slow failing read is not retried at once`() {
        store.failReads = true
        store.duringRead = { time.instant = time.instant.plusSeconds(10) }   // 읽다가 10초가 갔다(ttl 5초보다 길다)

        control.snapshot()
        control.snapshot()

        assertEquals(1, store.reads)
    }

    @Test
    fun `a change made while another read is on its way is not hidden by that older read`() {
        val inRead = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        store.duringRead = { inRead.countDown(); release.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        val reader = Thread { control.snapshot() }.also { it.start() }
        assertTrue(inRead.await(10, java.util.concurrent.TimeUnit.SECONDS))
        store.duringRead = {}

        control.setFlag("acc_op", "late", enabled = true)   // 읽기 도중의 쓰기
        release.countDown()
        reader.join(10_000)

        assertTrue(flags.isEnabled("late"), "the next read sees it, the older read's result is not kept for a whole ttl")
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
            override fun find(name: String) = current().find(name)
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
