package dev.sumin.skeleton.appcontrol.jdbc

import dev.sumin.skeleton.appcontrol.AppControl
import dev.sumin.skeleton.appcontrol.AppControlEntry
import dev.sumin.skeleton.appcontrol.AppControlProperties
import dev.sumin.skeleton.appcontrol.AppControlStore
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper

/** 두 방언에서 같은 뜻인지 - 덮어쓰기 · 처음 만드는 두 요청이 겹쳐도 오류 없음 · 운영 기록이 실패하면 값도 남지 않음 · 한글 문구 왕복 · 두 인스턴스가 한 DB 를 봄 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [AppControlJdbcTestApplication::class])
class AppControlStoreDbTest {
    @Autowired lateinit var store: AppControlStore
    @Autowired lateinit var mapper: ObjectMapper

    private val t0 = Instant.parse("2026-11-16T09:00:00.123456Z")

    private fun entry(name: String, payload: String = "{}", by: String = "acc_1", at: Instant = t0) = AppControlEntry(name, payload, by, at)

    @Test
    fun `a row round-trips with microseconds and is listed by name`() {
        store.put(entry("platform:rt-ios", """{"minVersion":"1.9"}"""))
        store.put(entry("flag:rt-beta", """{"enabled":true}"""))

        val mine = store.all().filter { it.name.contains("rt-") }
        assertEquals(listOf("flag:rt-beta", "platform:rt-ios"), mine.map { it.name })
        assertEquals(entry("platform:rt-ios", """{"minVersion":"1.9"}"""), mine.last())
    }

    @Test
    fun `putting an existing name overwrites payload, author and time`() {
        store.put(entry("flag:over", """{"enabled":false}""", by = "acc_1"))
        store.put(entry("flag:over", """{"enabled":true}""", by = "acc_2", at = t0.plusSeconds(60)))

        val row = store.all().single { it.name == "flag:over" }
        assertEquals(entry("flag:over", """{"enabled":true}""", by = "acc_2", at = t0.plusSeconds(60)), row)
    }

    @Test
    fun `two first writes of the same name at once end without an error and with one row`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map { n -> pool.submit { start.await(); store.put(entry("flag:race", """{"enabled":true}""", by = "acc_$n")) } }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, store.all().count { it.name == "flag:race" })
    }

    @Test
    fun `find reads one row by name and says nothing for a missing one`() {
        store.put(entry("platform:find-me", """{"minVersion":"1.0"}""", by = "acc_7"))

        assertEquals(entry("platform:find-me", """{"minVersion":"1.0"}""", by = "acc_7"), store.find("platform:find-me"))
        assertEquals(null, store.find("platform:nobody"))
    }

    @Test
    fun `a patch merges with the stored platform in the database too`() {
        val control = AppControl(store, OperatorAuditLog { }, TimeProvider.fixed(t0), AppControlProperties(), mapper)
        control.setPlatform("acc_op", "ios", "1.9", "1.10", "https://apps.apple.com/app/id1")

        val merged = control.patchPlatform("acc_op", "ios", null, "1.11", null)

        assertEquals("1.9", merged.minVersion)
        assertEquals("1.11", AppControl(store, OperatorAuditLog { }, TimeProvider.fixed(t0), AppControlProperties(), mapper).snapshot().platforms.getValue("ios").recommendedVersion)
    }

    @Test
    fun `delete says whether it removed a row`() {
        store.put(entry("flag:gone"))

        assertTrue(store.delete("flag:gone"))
        assertFalse(store.delete("flag:gone"))
    }

    @Test
    fun `a long Korean maintenance message survives the round trip`() {
        val control = AppControl(store, OperatorAuditLog { }, TimeProvider.fixed(t0), AppControlProperties(), mapper)
        val message = "점검 중입니다. 밤 12시까지 " + "가".repeat(400)

        control.setMaintenance("acc_op", enabled = true, message = message, endsAt = t0.plusSeconds(3600))

        val other = AppControl(store, OperatorAuditLog { }, TimeProvider.fixed(t0), AppControlProperties(), mapper)
        assertEquals(message, other.snapshot().maintenance.message)
        assertEquals(t0.plusSeconds(3600), other.snapshot().maintenance.endsAt)
    }

    @Test
    fun `a second instance with its own cache sees what the first one stored`() {
        val first = AppControl(store, OperatorAuditLog { }, TimeProvider.fixed(t0), AppControlProperties(), mapper)
        val second = AppControl(store, OperatorAuditLog { }, TimeProvider.fixed(t0), AppControlProperties(), mapper)

        first.setFlag("acc_op", "shared-beta", enabled = true, clientVisible = true, description = "Beta")

        assertTrue(second.snapshot().flags.getValue("shared-beta").enabled)
        assertEquals("Beta", second.snapshot().flags.getValue("shared-beta").description)
    }

    @Test
    fun `when the operator log fails the change is not stored`() {
        val control = AppControl(store, OperatorAuditLog { error("audit down") }, TimeProvider.fixed(t0), AppControlProperties(), mapper)

        assertFailsWith<IllegalStateException> { control.setFlag("acc_op", "rolled-back", enabled = true) }

        assertTrue(store.all().none { it.name == "flag:rolled-back" })
    }
}
