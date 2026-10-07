package dev.sumin.skeleton.migration.flyway

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.api.CoreErrorCode
import org.flywaydb.core.api.ErrorDetails
import org.flywaydb.core.api.exception.FlywayValidateException
import org.springframework.core.io.support.SpringFactoriesLoader

/** 동결된 마이그레이션이 바뀌어 검증이 실패하면, Flyway 의 긴 메시지 대신 무엇을 하라는 안내가 나온다 (docs/schema-management.md). */
class FlywayValidationFailureAnalyzerTest {
    private val failure = FlywayValidateException(ErrorDetails(CoreErrorCode.VALIDATE_ERROR, "checksum mismatch"), "Validate failed: Migrations have failed validation")

    @Test
    fun `a validation failure says the migration changed and what to do instead`() {
        val analysis = assertNotNull(FlywayValidationFailureAnalyzer().analyze(RuntimeException("startup", failure)))
        assertTrue("바뀌었" in analysis.description.orEmpty() && "checksum mismatch" in analysis.description.orEmpty(), analysis.description)
        assertTrue("새 V 파일" in analysis.action.orEmpty() && "docker compose down -v" in analysis.action.orEmpty() && "migrations-lock.pl --rewrite" in analysis.action.orEmpty(), analysis.action)
    }

    @Test
    fun `other failures are not claimed`() {
        assertNull(FlywayValidationFailureAnalyzer().analyze(IllegalStateException("something else")))
    }

    @Test
    fun `it is registered in spring factories`() {
        val loaded = SpringFactoriesLoader.forDefaultResourceLocation(javaClass.classLoader)
            .load(org.springframework.boot.diagnostics.FailureAnalyzer::class.java, SpringFactoriesLoader.ArgumentResolver.none(), SpringFactoriesLoader.FailureHandler.logging(org.apache.commons.logging.LogFactory.getLog(javaClass)))
        assertTrue(loaded.any { it is FlywayValidationFailureAnalyzer }, loaded.toString())
    }
}
