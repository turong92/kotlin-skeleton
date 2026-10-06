package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentErasureTest {
    private val ctx = ConsentContext("203.0.113.9", "ua")

    private fun agreed(f: ConsentFixture, id: String) =
        f.service.record(Subject.account(id), listOf(ConsentClaim("terms", "v2"), ConsentClaim("privacy", "v1"), ConsentClaim("marketing", "v1")), ctx, "sign-up")

    @Test
    fun `anonymize keeps the proof without the person - tombstone for the subject, no ip, no user agent, everything else untouched`() {
        val f = ConsentFixture()
        agreed(f, "acc_1")
        agreed(f, "acc_2")
        val before = f.store.rows.filter { it.subject.id == "acc_1" }

        ConsentErasureListener(f.store, ErasureMode.ANONYMIZE).erase(ErasureRequest("acc_1", AccountTombstone.of("acc_1")))

        val tomb = AccountTombstone.of("acc_1")
        val after = f.store.rows.filter { it.subject.id == tomb }
        assertEquals(3, after.size)
        assertTrue(after.all { it.ip == null && it.userAgent == null && it.subject.type == "account" })
        assertEquals(before.map { Triple(it.type, it.version, it.sha256) }, after.map { Triple(it.type, it.version, it.sha256) })
        assertEquals(before.map { it.at }, after.map { it.at })
        assertEquals(3, f.store.rows.count { it.subject.id == "acc_2" && it.ip != null }, "other subjects are untouched")
        assertEquals(0, f.store.rows.count { it.subject.id == "acc_1" })
    }

    @Test
    fun `delete mode removes the rows after anonymizing them`() {
        val f = ConsentFixture()
        agreed(f, "acc_1")
        agreed(f, "acc_2")

        ConsentErasureListener(f.store, ErasureMode.DELETE).erase(ErasureRequest("acc_1", AccountTombstone.of("acc_1")))

        assertEquals(3, f.store.rows.size)
        assertTrue(f.store.rows.all { it.subject.id == "acc_2" })
    }

    @Test
    fun `erasing twice is harmless, the account purge retries a failed run`() {
        val f = ConsentFixture()
        agreed(f, "acc_1")
        val listener = ConsentErasureListener(f.store, ErasureMode.ANONYMIZE)
        val request = ErasureRequest("acc_1", AccountTombstone.of("acc_1"))
        listener.erase(request)
        listener.erase(request)
        assertEquals(3, f.store.rows.size)
        assertEquals("legal", listener.name)
    }

    @Test
    fun `the data export hands the person their own events including what was stored about the client`() {
        val f = ConsentFixture()
        agreed(f, "acc_1")
        agreed(f, "acc_2")

        val exporter = ConsentDataExporter(f.store)
        val section = exporter.export("acc_1")

        assertEquals("legal", exporter.section)
        @Suppress("UNCHECKED_CAST")
        val events = section["consents"] as List<Map<String, Any?>>
        assertEquals(3, events.size)
        assertEquals(listOf("terms", "privacy", "marketing"), events.map { it["type"] })
        assertEquals("203.0.113.9", events.first()["ip"])
        assertEquals("v2", events.first()["version"])
        assertEquals("AGREED", events.first()["action"])
    }

    @Test
    fun `personal data older than the retention is scrubbed, younger rows keep it, and a sweep runs at most once per interval`() {
        val f = ConsentFixture()
        agreed(f, "acc_1")
        f.clock.now = f.clock.now.plus(Duration.ofDays(200))   // terms v3 is in force by now
        f.service.record(Subject.account("acc_2"), listOf(ConsentClaim("privacy", "v1"), ConsentClaim("marketing", "v1")), ctx, "sign-up")
        val retention = ConsentRetention(f.store, f.clock, Duration.ofDays(365), Duration.ofHours(1))

        f.clock.now = f.clock.now.plus(Duration.ofDays(200))   // acc_1: 400 days, acc_2: 200 days
        assertEquals(3, retention.runIfDue())

        assertTrue(f.store.rows.filter { it.subject.id == "acc_1" }.all { it.ip == null && it.userAgent == null })
        assertTrue(f.store.rows.filter { it.subject.id == "acc_2" }.all { it.ip != null })
        assertEquals(3, f.store.rows.count { it.subject.id == "acc_1" }, "the proof itself stays")
        assertNull(retention.runIfDue(), "not due again within the hour")
        f.clock.now = f.clock.now.plus(Duration.ofHours(2))
        assertEquals(0, retention.runIfDue())
    }

    @Test
    fun `a zero retention keeps personal data for ever`() {
        val f = ConsentFixture()
        agreed(f, "acc_1")
        f.clock.now = f.clock.now.plus(Duration.ofDays(4000))
        assertNull(ConsentRetention(f.store, f.clock, Duration.ZERO, Duration.ofHours(1)).runIfDue())
        assertTrue(f.store.rows.all { it.ip != null })
    }

    @Test
    fun `recording sweeps expired personal data on the way, so a long-running server needs no scheduler`() {
        val f = ConsentFixture()
        agreed(f, "acc_1")
        f.clock.now = f.clock.now.plus(Duration.ofDays(400))
        val service = ConsentService(f.catalog, f.rules, f.store, f.clock, f.options, ConsentRetention(f.store, f.clock, Duration.ofDays(365), Duration.ofHours(1)))

        service.record(Subject.account("acc_2"), listOf(ConsentClaim("marketing", "v1")), ctx, "settings")

        assertTrue(f.store.rows.filter { it.subject.id == "acc_1" }.all { it.ip == null && it.userAgent == null })
        assertEquals("203.0.113.9", f.store.rows.single { it.subject.id == "acc_2" }.ip, "the fresh row keeps its ip")
    }
}
