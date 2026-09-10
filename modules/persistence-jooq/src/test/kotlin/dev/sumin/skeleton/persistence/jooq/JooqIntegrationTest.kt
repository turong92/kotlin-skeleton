package dev.sumin.skeleton.persistence.jooq

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jooq.generated.tables.JooqProbe.JOOQ_PROBE
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * DB 없이 생성한 코드(DDLDatabase) 로 실제 MySQL(Testcontainers) 에 읽고 쓴다.
 * - *_at 컬럼은 Instant 로 생성됐고 JVM 이 서울이어도 UTC 리터럴로 왕복
 * - created_at/updated_at 은 리스너가 채움
 */
@Import(JooqTestcontainers::class, JooqIntegrationTest.FixedTime::class)
@SpringBootTest(classes = [JooqTestApplication::class])
class JooqIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider.fixed(NOW)
    }

    @Autowired
    lateinit var dsl: DSLContext

    @Test
    fun `insert 하면 audit 이 채워지고 Instant 가 UTC 로 왕복한다`() {
        val happened = Instant.parse("2026-03-01T12:00:00.123456Z")
        val record = dsl.newRecord(JOOQ_PROBE).apply {
            name = "probe"
            happenedAt = happened
            localWall = LocalDateTime.parse("2026-10-05T21:00")
        }
        record.store()
        assertNotNull(record.id)

        val loaded = dsl.selectFrom(JOOQ_PROBE).where(JOOQ_PROBE.ID.eq(record.id)).fetchSingle()
        assertEquals(happened, loaded.happenedAt)
        assertEquals(LocalDateTime.parse("2026-10-05T21:00"), loaded.localWall)
        assertEquals(NOW, loaded.createdAt)
        assertEquals(NOW, loaded.updatedAt)

        val raw = dsl.fetchValue("select concat(happened_at, '|', local_wall, '|', created_at) from jooq_probe where id = ?", record.id) as String
        assertEquals("2026-03-01 12:00:00.123456|2026-10-05 21:00:00.000000|2026-09-10 00:00:00.000000", raw)
        assertEquals("+00:00", dsl.fetchValue("select @@session.time_zone") as String)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-09-10T00:00:00Z")
        private lateinit var original: TimeZone

        @JvmStatic @BeforeAll
        fun seoul() { original = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul")) }

        @JvmStatic @AfterAll
        fun restore() { TimeZone.setDefault(original) }
    }
}
