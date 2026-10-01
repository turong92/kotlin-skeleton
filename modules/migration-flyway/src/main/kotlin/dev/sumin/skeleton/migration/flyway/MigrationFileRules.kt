package dev.sumin.skeleton.migration.flyway

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * 마이그레이션 파일 규칙 (docs/schema-management.md):
 * - 위치: src/<sourceSet>/resources/db/migration/<vendor>/   (vendor = postgresql | mysql)
 * - 이름: V<UTC yyyyMMddHHmmss>__<snake_case>.sql, 시각은 실제로 존재하는 값
 * - 같은 vendor 안에서 버전은 레포 전체(앱 · 모듈 · 테스트 리소스)에서 하나
 * - 두 vendor 폴더를 가진 소스 디렉토리는 버전 · 이름이 짝을 이룬다
 */
object MigrationFileRules {
    val NAME = Regex("""^V(\d{14})__[a-z0-9]+(_[a-z0-9]+)*\.sql$""")
    val VENDORS = setOf("postgresql", "mysql")
    private val LOCATION = Regex("""^(.*/src/[^/]+/resources/db/migration)/(.+)$""")
    private val TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val SKIP_DIRS = setOf("build", ".gradle", ".git", ".kotlin", "node_modules", "out")

    private data class Migration(val base: String, val vendor: String, val version: String, val name: String, val display: String)

    fun violations(root: Path): List<String> {
        val violations = mutableListOf<String>()
        val migrations = mutableListOf<Migration>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "sql" }
                .filter { path -> root.relativize(path).none { it.name in SKIP_DIRS } }
                .forEach { path ->
                    val rel = root.relativize(path).invariantSeparatorsPathString
                    val match = LOCATION.matchEntire(rel) ?: return@forEach
                    val (base, rest) = match.destructured
                    val parts = rest.split('/')
                    if (parts.size != 2 || parts[0] !in VENDORS) {
                        violations += "$rel: must be under db/migration/<vendor>/ with vendor in $VENDORS"
                        return@forEach
                    }
                    val name = NAME.matchEntire(parts[1])
                    if (name == null) {
                        violations += "$rel: name must match V<yyyyMMddHHmmss UTC>__<snake_case>.sql"
                        return@forEach
                    }
                    val version = name.groupValues[1]
                    if (runCatching { LocalDateTime.parse(version, TIMESTAMP) }.isFailure) {
                        violations += "$rel: $version is not a valid UTC timestamp"
                        return@forEach
                    }
                    migrations += Migration(base, parts[0], version, parts[1], rel)
                }
        }
        migrations.groupBy { it.vendor to it.version }.values.filter { it.size > 1 }.forEach { same ->
            violations += "duplicate version ${same.first().version} in ${same.first().vendor}: ${same.joinToString { it.display }}"
        }
        migrations.groupBy { it.base }.forEach { (base, inBase) ->
            val byVendor = inBase.groupBy({ it.vendor }, { it.name })
            if (byVendor.size > 1) {
                val all = byVendor.values.flatten().toSet()
                byVendor.forEach { (vendor, names) ->
                    (all - names.toSet()).forEach { missing ->
                        violations += "$base/$vendor: missing $missing (every vendor folder in one module pairs up)"
                    }
                }
            }
        }
        return violations.sorted()
    }
}
