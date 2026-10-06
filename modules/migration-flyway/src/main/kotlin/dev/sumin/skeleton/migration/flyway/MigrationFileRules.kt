package dev.sumin.skeleton.migration.flyway

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * 마이그레이션 파일 규칙 (docs/schema-management.md):
 * - 위치: src/<sourceSet>/resources/db/migration/<vendor>/   (vendor = postgresql | mysql)
 * - 이름: V<UTC yyyyMMddHHmmss>__<snake_case>.sql, 시각은 실제로 존재하는 값
 * - 같은 vendor 안에서 버전은 레포 전체(앱 · 모듈 · 테스트 리소스)에서 하나
 * - 두 vendor 폴더를 가진 소스 디렉토리는 버전 · 이름이 짝을 이룬다
 * - MySQL 파일의 인라인 `index` · `unique key` · `foreign key` · `collate` 는 `/* [jooq ignore start] */ … /* [jooq ignore stop] */` 안에 둔다
 *   (jOOQ DDLDatabase 가 못 읽는 문법 — docs/schema-management.md · docs/persistence-jooq.md)
 *
 * 훑지 않는 곳 (들어가지도 않는다): [SKIP_DIRS](빌드 출력 · node_modules 등), `.claude`(Claude Code 워크트리),
 * 루트가 아닌데 `.git` 이 있는 디렉토리(레포 안의 다른 워크트리 · 레포 — 저마다 같은 마이그레이션 사본을 가져
 * 「같은 버전」으로 build 를 깬다).
 */
object MigrationFileRules {
    val NAME = Regex("""^V(\d{14})__[a-z0-9]+(_[a-z0-9]+)*\.sql$""")
    val VENDORS = setOf("postgresql", "mysql")
    private val LOCATION = Regex("""^(.*/src/[^/]+/resources/db/migration)/(.+)$""")
    private val TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val SKIP_DIRS = setOf("build", ".gradle", ".git", ".kotlin", "node_modules", "out", ".claude")

    private data class Migration(val base: String, val vendor: String, val version: String, val name: String, val display: String)

    fun violations(root: Path): List<String> {
        val violations = mutableListOf<String>()
        val migrations = mutableListOf<Migration>()
        sqlFiles(root).forEach { path ->
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
            if (parts[0] == "mysql") jooqUnparsable(path)?.let { violations += "$rel: $it must sit between /* [jooq ignore start] */ and /* [jooq ignore stop] */" }
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

    private val IGNORED = Regex("""/\*\s*\[jooq ignore start]\s*\*/.*?/\*\s*\[jooq ignore stop]\s*\*/""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val UNPARSABLE = listOf(
        "inline index" to Regex("""(?im)^\s*(,\s*)?(unique\s+)?(index|key)\s+\w+\s*\("""),
        "foreign key" to Regex("""(?im)^\s*(,\s*)?(constraint\s+\w+\s+)?foreign\s+key\b"""),
        "collate" to Regex("""(?i)\bcollate\b"""),
    )

    /** 마커 · 주석을 걷어 낸 뒤에도 남은 jOOQ 가 못 읽는 문법의 이름, 없으면 null */
    private fun jooqUnparsable(path: Path): String? {
        val sql = Files.readString(path).replace(IGNORED, " ").replace(BLOCK_COMMENT, " ").lines().joinToString("\n") { it.substringBefore("--") }
        return UNPARSABLE.firstOrNull { (_, regex) -> regex.containsMatchIn(sql) }?.first
    }

    private fun sqlFiles(root: Path): List<Path> {
        val files = mutableListOf<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && (dir.name in SKIP_DIRS || dir.resolve(".git").exists())) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && file.extension == "sql") files.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }
}
