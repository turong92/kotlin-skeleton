package dev.sumin.skeleton.persistence.jooq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 이 모듈의 main 에는 런타임 부품(audit 리스너 · 변환기 · 자동 구성)만 있다.
 * 시험용 예시 스키마와 거기서 생성한 클래스는 test 소스 세트에만 있고, 모듈 jar 로 새어 나가지 않는다.
 * (DB 없이 도는 테스트다 — JooqIntegrationTest 와 달리 Docker 가 필요 없다)
 */
class JooqModuleLayoutTest {
    private fun isMainOutput(url: java.net.URL) = Regex("/(classes/(kotlin|java)|resources)/main/").containsMatchIn(url.path)

    @Test
    fun `main output has the runtime pieces`() {
        listOf("JooqAuditRecordListener", "UtcInstantConverter", "JooqAutoConfiguration").forEach { name ->
            val url = javaClass.classLoader.getResource("dev/sumin/skeleton/persistence/jooq/$name.class")
            assertTrue(url != null && isMainOutput(url), "$name must be in main, found: $url")
        }
    }

    @Test
    fun `probe ddl is not shipped from main resources`() {
        val urls = javaClass.classLoader.getResources("db/jooq-probe-postgresql.sql").toList()
        assertTrue(urls.isNotEmpty(), "the test source set must carry the probe ddl")
        assertFalse(urls.any(::isMainOutput), "probe ddl must live in src/test/resources, found: $urls")
    }

    @Test
    fun `generated probe classes are not shipped from main output`() {
        val url = javaClass.classLoader.getResource("dev/sumin/skeleton/persistence/jooq/generated/tables/JooqProbe.class")
        assertTrue(url != null, "generated classes must be on the test classpath")
        assertFalse(isMainOutput(url), "generated classes must be compiled into the test source set, found: $url")
    }

    @Test
    fun `module migrations that were on disk at build time were parsed into generated tables`() {
        // build.gradle.kts 가 형제 모듈의 마이그레이션 폴더가 있을 때만 이 값을 채운다 (고르지 않은 모듈은 비어 있다)
        val expected = System.getProperty("skeleton.jooq.expectedModuleTables").orEmpty().split(',').filter { it.isNotBlank() }
        expected.forEach { table ->
            val className = table.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }   // jobs → SkeletonJobs (jOOQ 기본 이름 규칙)
            assertTrue(
                javaClass.classLoader.getResource("dev/sumin/skeleton/persistence/jooq/generated/tables/$className.class") != null,
                "$table was not generated from its module migration",
            )
        }
        assertEquals(expected.distinct(), expected)
    }
}
