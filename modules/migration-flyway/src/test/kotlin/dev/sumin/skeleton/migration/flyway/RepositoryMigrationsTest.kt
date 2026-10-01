package dev.sumin.skeleton.migration.flyway

import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** 이 레포 전체(앱 · 모든 모듈)의 마이그레이션이 이름 규칙 · 중복 · 짝 규칙을 지키는지. ./gradlew build 에서 돈다. */
class RepositoryMigrationsTest {
    @Test
    fun `repository migrations follow the rules`() {
        val root = Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })
        assertEquals(emptyList(), MigrationFileRules.violations(root))
    }
}
