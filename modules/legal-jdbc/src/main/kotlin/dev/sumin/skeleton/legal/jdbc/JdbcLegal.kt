package dev.sumin.skeleton.legal.jdbc

import dev.sumin.skeleton.legal.ConsentAction
import dev.sumin.skeleton.legal.ConsentEvent
import dev.sumin.skeleton.legal.ConsentPage
import dev.sumin.skeleton.legal.ConsentSearch
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.DocumentStatus
import dev.sumin.skeleton.legal.LedgerEntry
import dev.sumin.skeleton.legal.LegalLedger
import dev.sumin.skeleton.legal.NewConsentEvent
import dev.sumin.skeleton.legal.Subject
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * `legal_consents` — 더하기만 하는 동의 사건. 이 클래스에 고치는 문장은 익명화 · 개인정보 비우기뿐이고, DB 트리거가 그 밖의 고치기 · 지우기를 막는다.
 * 순번(`seq`)이 유니크 키의 일부라 같은 순번을 동시에 넣으려는 쪽은 [append] 에서 false 를 받는다.
 * 주의: 이미 열린 트랜잭션 안에서 유니크 위반이 나면 PostgreSQL 은 그 트랜잭션을 중단시킨다 — 가입(새 주체)처럼 경합이 없는 쓰기만 트랜잭션 안에서 부른다.
 */
class JdbcConsentStore(private val jdbc: JdbcClient, private val dialect: SqlDialect) : ConsentStore {
    override fun latest(subject: Subject, types: Collection<String>, referenceId: String?): Map<String, ConsentEvent> {
        if (types.isEmpty()) return emptyMap()
        return jdbc.sql(
            """select c.* from legal_consents c
               join (select document_type, max(seq) as m from legal_consents
                     where subject_type = :st and subject_id = :si and reference_id = :ref and document_type in (:types)
                     group by document_type) x on c.document_type = x.document_type and c.seq = x.m
               where c.subject_type = :st and c.subject_id = :si and c.reference_id = :ref""",
        ).param("st", subject.type).param("si", subject.id).param("ref", referenceId.orEmpty()).param("types", types.toList())
            .query(::map).list().associateBy { it.type }
    }

    override fun append(event: NewConsentEvent): Boolean = try {
        jdbc.sql(
            """insert into legal_consents (subject_type, subject_id, document_type, version, content_sha256, locale, action, source, reference_id, seq, ip, user_agent, created_at)
               values (:st, :si, :dt, :v, :h, :l, :a, :s, :ref, :seq, :ip, :ua, :at)""",
        ).param("st", event.subject.type).param("si", event.subject.id).param("dt", event.type).param("v", event.version).param("h", event.sha256)
            .param("l", event.locale).param("a", event.action.name).param("s", event.source).param("ref", event.referenceId.orEmpty()).param("seq", event.seq)
            .param("ip", event.ip, Types.VARCHAR).param("ua", event.userAgent, Types.VARCHAR).param("at", dialect.instantParam(event.at)).update()
        true
    } catch (e: DuplicateKeyException) {
        false
    }

    override fun history(subject: Subject, page: Int, size: Int): ConsentPage =
        search(ConsentSearch(subject.type, subject.id), page, size)

    override fun search(search: ConsentSearch, page: Int, size: Int): ConsentPage {
        val where = buildList {
            if (search.subjectType != null) add("subject_type = :st")
            if (search.subjectId != null) add("subject_id = :si")
            if (search.type != null) add("document_type = :dt")
            if (search.action != null) add("action = :a")
        }.let { if (it.isEmpty()) "" else " where " + it.joinToString(" and ") }
        fun <S : JdbcClient.StatementSpec> S.bind(): S = apply {
            search.subjectType?.let { param("st", it) }
            search.subjectId?.let { param("si", it) }
            search.type?.let { param("dt", it) }
            search.action?.let { param("a", it.name) }
        }
        val total = jdbc.sql("select count(*) from legal_consents$where").bind().query(Long::class.java).single()
        val items = jdbc.sql("select * from legal_consents$where order by id desc limit :limit offset :offset").bind()
            .param("limit", size).param("offset", page * size).query(::map).list()
        return ConsentPage(items, total)
    }

    override fun countSince(subject: Subject, since: Instant): Int =
        jdbc.sql("select count(*) from legal_consents where subject_type = :st and subject_id = :si and created_at >= :since")
            .param("st", subject.type).param("si", subject.id).param("since", dialect.instantParam(since)).query(Int::class.java).single()

    override fun anonymize(subject: Subject, tombstone: String): Int =
        jdbc.sql("update legal_consents set subject_id = :t, ip = null, user_agent = null where subject_type = :st and subject_id = :si")
            .param("t", tombstone).param("st", subject.type).param("si", subject.id).update()

    override fun deleteAnonymized(tombstone: String): Int =
        jdbc.sql("delete from legal_consents where subject_id = :t and subject_id like 'deleted:%'").param("t", tombstone).update()

    override fun scrubPersonalData(before: Instant): Int =
        jdbc.sql("update legal_consents set ip = null, user_agent = null where created_at < :before and (ip is not null or user_agent is not null)")
            .param("before", dialect.instantParam(before)).update()

    override fun export(subject: Subject): List<ConsentEvent> =
        jdbc.sql("select * from legal_consents where subject_type = :st and subject_id = :si order by id").param("st", subject.type).param("si", subject.id).query(::map).list()

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = ConsentEvent(
        id = rs.getLong("id"),
        subject = Subject(rs.getString("subject_type"), rs.getString("subject_id")),
        type = rs.getString("document_type"),
        version = rs.getString("version"),
        sha256 = rs.getString("content_sha256"),
        locale = rs.getString("locale"),
        action = ConsentAction.valueOf(rs.getString("action")),
        source = rs.getString("source"),
        referenceId = rs.getString("reference_id").takeIf { it.isNotEmpty() },
        seq = rs.getInt("seq"),
        ip = rs.getString("ip"),
        userAgent = rs.getString("user_agent"),
        at = dialect.readInstant(rs, "created_at")!!,
    )
}

/** `legal_document_versions` — 판 장부. 줄을 더하기만 한다 (트리거가 그 밖을 막는다) */
class JdbcLegalLedger(private val jdbc: JdbcClient, private val dialect: SqlDialect) : LegalLedger {
    override fun find(type: String, version: String, locale: String): LedgerEntry? =
        jdbc.sql("select * from legal_document_versions where document_type = :t and version = :v and locale = :l")
            .param("t", type).param("v", version).param("l", locale).query(::map).optional().orElse(null)

    override fun all(): List<LedgerEntry> = jdbc.sql("select * from legal_document_versions order by id").query(::map).list()

    override fun append(entry: LedgerEntry): Boolean = try {
        jdbc.sql(
            """insert into legal_document_versions (document_type, version, locale, status, content_sha256, effective_from, recorded_at)
               values (:t, :v, :l, :s, :h, :e, :r)""",
        ).param("t", entry.type).param("v", entry.version).param("l", entry.locale).param("s", entry.status.name).param("h", entry.sha256)
            .param("e", dialect.instantParam(entry.effectiveFrom), Types.TIMESTAMP_WITH_TIMEZONE).param("r", dialect.instantParam(entry.recordedAt)).update()
        true
    } catch (e: DuplicateKeyException) {
        false
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = LedgerEntry(
        rs.getString("document_type"), rs.getString("version"), rs.getString("locale"), DocumentStatus.valueOf(rs.getString("status")),
        rs.getString("content_sha256"), dialect.readInstant(rs, "effective_from"), dialect.readInstant(rs, "recorded_at")!!,
    )
}
