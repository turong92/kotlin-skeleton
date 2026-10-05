package dev.sumin.skeleton.common.deploy

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class DeployGuardFailureAnalyzerTest {
    @Test
    fun `lists each problem under its guard and says how to fix or opt out`() {
        val failure = DeployGuardViolationException(
            listOf(DeployFinding("auth", "SKELETON_AUTH_JWT_SECRET is blank"), DeployFinding("storage", "SKELETON_STORAGE_S3_BUCKET is not set")),
        )

        val analysis = DeployGuardFailureAnalyzer().analyze(RuntimeException("wrapped", failure))!!

        assertThat(analysis.description).contains("[auth] SKELETON_AUTH_JWT_SECRET is blank").contains("[storage] SKELETON_STORAGE_S3_BUCKET is not set")
        assertThat(analysis.action).contains("SKELETON_ENV")
    }

    @Test
    fun `ignores unrelated failures`() {
        assertThat(DeployGuardFailureAnalyzer().analyze(IllegalStateException("boom"))).isNull()
    }
}
