package dev.sumin.skeleton.migration.flyway

import org.flywaydb.core.api.exception.FlywayValidateException
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/**
 * 이미 적용된 마이그레이션이 바뀌었거나 사라져 Flyway 검증이 실패했을 때, 긴 Flyway 메시지 대신 무엇을 하라는 안내를 보인다.
 * 동결된 마이그레이션은 로컬에서도 DB 를 조용히 지우지 않는다 — 기동이 멈추고, 고르는 것은 사람이다 (docs/schema-management.md).
 */
class FlywayValidationFailureAnalyzer : AbstractFailureAnalyzer<FlywayValidateException>() {
    override fun analyze(rootFailure: Throwable, cause: FlywayValidateException): FailureAnalysis =
        FailureAnalysis(
            "이미 적용된 마이그레이션이 바뀌었거나 사라졌다 (Flyway 검증 실패): ${cause.message?.lines()?.filter { it.isNotBlank() }?.joinToString(" | ")?.take(800)}",
            "이미 배포됐을 수 있는 마이그레이션은 고치지 않는다 — 고친 것을 되돌리고 새 V 파일로 추가하라 (./gradlew newMigration -Pname=<설명>). " +
                "정말 버려도 되는 로컬 DB 라면 `docker compose down -v` 로 DB 를 지우고 다시 띄운다 " +
                "(고친 파일을 잠금에도 반영하려면 perl scripts/migrations-lock.pl --rewrite <경로>). " +
                "자세한 내용: docs/schema-management.md",
            cause,
        )
}
