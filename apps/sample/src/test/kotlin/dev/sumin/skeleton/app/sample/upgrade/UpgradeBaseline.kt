package dev.sumin.skeleton.app.sample.upgrade

import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * 업그레이드 테스트의 기준선 — 「기준선 스키마 + 대표 데이터 → 최신까지 migrate → 데이터가 살아 있다」 (docs/schema-management.md).
 *
 * 기준선 버전은 `migrations.lock` 의 `baseline` 줄 한 곳이 정한다. 마이그레이션은 잠긴 뒤 바뀌지 않으므로 이 버전까지의 스키마에 넣는 아래의 INSERT 도
 * 바뀌지 않는다 — 앞으로 더해지는 V 파일이 이 데이터 위에서 돈다 (NOT NULL 열을 기본값 없이 더하는 마이그레이션은 여기서 실패한다).
 * 기준선을 올리면(= 더 새 스키마에서 시작) 그 스키마에 맞게 이 데이터도 같이 고친다.
 */
object UpgradeBaseline {
    /** 모듈별 대표 행 — PostgreSQL · MySQL 이 같은 문장을 쓴다 (리터럴 · 표준 SQL 만) */
    val ROWS: List<String> = listOf(
        // account-jdbc
        "insert into accounts (id, email, email_verified, status, display_name, display_name_key, display_tag, locale, time_zone, created_at, updated_at) " +
            "values ('acc_upgrade', 'upgrade@example.com', false, 'ACTIVE', 'Upgrader', 'upgrader', '0000', 'ko', 'Asia/Seoul', '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
        "insert into account_roles (account_id, role) values ('acc_upgrade', 'ADMIN')",
        "insert into account_identities (id, account_id, method, subject, verified, secret, created_at) " +
            "values ('idn_upgrade', 'acc_upgrade', 'password', 'upgrade@example.com', true, '{bcrypt}upgrade', '2026-10-01 00:00:00')",
        "insert into account_tokens (token_hash, purpose, subject, account_id, created_at, expires_at) " +
            "values ('${"a".repeat(64)}', 'email_verify', 'upgrade@example.com', 'acc_upgrade', '2026-10-01 00:00:00', '2036-10-01 00:00:00')",
        "insert into account_audit (at, type, account_id, detail) values ('2026-10-01 00:00:00', 'LOGIN', 'acc_upgrade', 'baseline')",
        "insert into account_blocks (kind, hash, reason, created_at) values ('email', '${"b".repeat(64)}', 'baseline', '2026-10-01 00:00:00')",
        "insert into account_challenges (id, purpose, subject, account_id, code_hash, attempts_left, resends, created_at, expires_at, last_sent_at) " +
            "values ('chl_upgrade', 'reauth', 'acc_upgrade', 'acc_upgrade', '${"c".repeat(64)}', 5, 0, '2026-10-01 00:00:00', '2036-10-01 00:00:00', '2026-10-01 00:00:00')",
        // auth-session-jdbc
        "insert into auth_sessions (id, account_id, device_name, created_at, last_used_at, expires_at) " +
            "values ('ses_upgrade', 'acc_upgrade', 'baseline', '2026-10-01 00:00:00', '2026-10-01 00:00:00', '2036-10-01 00:00:00')",
        "insert into auth_refresh_tokens (token_hash, session_id, created_at) values ('${"d".repeat(64)}', 'ses_upgrade', '2026-10-01 00:00:00')",
        // board-jdbc
        "insert into boards (code, name, description, created_at) values ('upgrade', 'Upgrade', 'baseline board', '2026-10-01 00:00:00')",
        "insert into board_posts (board_code, author_id, title, body, status, comment_count, reaction_count, created_at, updated_at) " +
            "values ('upgrade', 'acc_upgrade', 'Before the upgrade', 'a post written on the baseline schema', 'PUBLISHED', 1, 1, '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
        "insert into board_comments (post_id, parent_id, root_id, depth, author_id, body, status, created_at, updated_at) " +
            "values ((select id from board_posts where title = 'Before the upgrade'), null, null, 0, 'acc_upgrade', 'a comment on the baseline schema', 'PUBLISHED', '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
        "insert into board_reactions (target_type, target_id, account_id, reaction_type, created_at) " +
            "values ('POST', (select id from board_posts where title = 'Before the upgrade'), 'acc_upgrade', 'LIKE', '2026-10-01 00:00:00')",
        // legal-jdbc
        "insert into legal_consents (subject_type, subject_id, document_type, version, content_sha256, locale, action, source, seq, created_at) " +
            "values ('account', 'acc_upgrade', 'terms', 'v1', '${"e".repeat(64)}', 'ko', 'AGREED', 'sign-up', 1, '2026-10-01 00:00:00')",
        "insert into legal_document_versions (document_type, version, locale, status, content_sha256, recorded_at) " +
            "values ('upgrade_probe', 'v1', 'ko', 'DRAFT', '${"e".repeat(64)}', '2026-10-01 00:00:00')",
        // notification-jdbc
        "insert into notification_inbox (recipient_id, event_id, topic, type, severity, title, message, payload_json, recipient_ids_json, event_created_at, created_at, updated_at) " +
            "values ('acc_upgrade', 'evt_upgrade', 'baseline', 'baseline.created', 'INFO', 'Before', 'written on the baseline schema', '{\"k\":\"v\"}', '[\"acc_upgrade\"]', '2026-10-01 00:00:00', '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
        // job-queue-jdbc (DONE — 워커가 모르는 작업을 집어 가지 않는다)
        "insert into jobs (job_type, payload_json, status, attempts, max_attempts, next_run_at, created_at, updated_at) " +
            "values ('baseline.job', '{}', 'DONE', 1, 3, '2026-10-01 00:00:00', '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
        // alert-jdbc
        "insert into alerts (kind, alert_key, severity, title, first_at, occurred_at, sent_at) " +
            "values ('BASELINE', 'k', 'WARN', 'Before', '2026-10-01 00:00:00', '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
    )

    /** 샘플 앱 자신의 표(PostgreSQL 전용) */
    val SAMPLE_ROWS: List<String> = listOf(
        "insert into notes (owner_id, title, body, status, created_at, updated_at) values ('acc_upgrade', 'Before the upgrade', 'a note on the baseline schema', 'ACTIVE', '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
    )

    /** 이 표들에 위 행이 하나씩(이상) 있어야 한다 — 업그레이드 뒤에도 */
    val TABLES: List<String> = listOf(
        "accounts", "account_roles", "account_identities", "account_tokens", "account_audit", "account_blocks", "account_challenges",
        "auth_sessions", "auth_refresh_tokens", "boards", "board_posts", "board_comments", "board_reactions",
        "legal_consents", "legal_document_versions", "notification_inbox", "jobs", "alerts",
    )

    val repoRoot: Path by lazy {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }.firstOrNull { Files.isRegularFile(it.resolve("migrations.lock")) }
            ?: error("migrations.lock 을 찾을 수 없다 (${Path.of("").toAbsolutePath()} 에서 위로)")
    }

    /** `migrations.lock` 의 `baseline <버전>` */
    val baselineVersion: String by lazy {
        Files.readAllLines(repoRoot.resolve("migrations.lock")).firstNotNullOfOrNull { Regex("""^baseline\s+(\d{14})\s*$""").matchEntire(it)?.groupValues?.get(1) }
            ?: error("migrations.lock 에 baseline 줄이 없다")
    }

    /** 기준선 버전까지만 migrate 하고 [ROWS] (+ [extraRows]) 를 넣는다. 이어서 호출하는 쪽이 최신까지 migrate 한다 */
    fun install(dataSource: DataSource, locations: List<String>, extraRows: List<String> = emptyList()) {
        Flyway.configure().dataSource(dataSource).locations(*locations.toTypedArray()).target(baselineVersion).load().migrate()
        val jdbc = JdbcClient.create(dataSource)
        (ROWS + extraRows).forEach { jdbc.sql(it).update() }
    }

    fun dataSource(db: org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails): DataSource =
        DriverManagerDataSource(requireNotNull(db.jdbcUrl), requireNotNull(db.username), requireNotNull(db.password))
}
