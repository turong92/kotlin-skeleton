package dev.sumin.skeleton.inquiry.jdbc

import dev.sumin.skeleton.inquiry.Inquiry
import dev.sumin.skeleton.inquiry.InquiryKind
import dev.sumin.skeleton.inquiry.InquiryRepository
import dev.sumin.skeleton.inquiry.InquiryStatus
import dev.sumin.skeleton.inquiry.NewInquiry
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder

class JdbcInquiryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : InquiryRepository {
    override fun create(inquiry: NewInquiry): Inquiry {
        val keys = GeneratedKeyHolder()
        jdbc.update(
            """
            insert into inquiries (kind, body, reply_email, account_id, context, lang, status, created_at)
            values (:kind, :body, :replyEmail, :accountId, :context, :lang, :status, :createdAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("kind", inquiry.kind.name)
                .addValue("body", inquiry.body)
                .addValue("replyEmail", inquiry.replyEmail)
                .addValue("accountId", inquiry.accountId)
                .addValue("context", inquiry.context)
                .addValue("lang", inquiry.lang)
                .addValue("status", InquiryStatus.NEW.name)
                .addValue("createdAt", dialect.instantParam(inquiry.at)),
            keys,
            arrayOf("id"),
        )
        return checkNotNull(find(keys.key!!.toLong())) { "inserted inquiry not found" }
    }

    override fun find(id: Long): Inquiry? =
        jdbc.query("select * from inquiries where id = :id", mapOf("id" to id)) { rs, _ -> rs.toInquiry() }.firstOrNull()

    override fun list(status: InquiryStatus?, limit: Int): List<Inquiry> =
        jdbc.query(
            """
            select * from inquiries
            ${if (status != null) "where status = :status" else ""}
            order by created_at desc, id desc
            limit :limit
            """.trimIndent(),
            MapSqlParameterSource().addValue("status", status?.name).addValue("limit", limit),
        ) { rs, _ -> rs.toInquiry() }

    override fun setStatus(id: Long, status: InquiryStatus, at: Instant): Inquiry? {
        val updated = jdbc.update(
            "update inquiries set status = :status, handled_at = :handledAt where id = :id",
            MapSqlParameterSource()
                .addValue("status", status.name)
                .addValue("handledAt", if (status == InquiryStatus.DONE) dialect.instantParam(at) else null)
                .addValue("id", id),
        )
        return if (updated == 0) null else find(id)
    }

    override fun detachAccount(accountId: String): Int =
        jdbc.update("update inquiries set account_id = null where account_id = :a", mapOf("a" to accountId))

    private fun ResultSet.toInquiry() = Inquiry(
        id = getLong("id"),
        kind = InquiryKind.valueOf(getString("kind")),
        body = getString("body"),
        replyEmail = getString("reply_email"),
        accountId = getString("account_id"),
        context = getString("context"),
        lang = getString("lang"),
        status = InquiryStatus.valueOf(getString("status")),
        createdAt = dialect.readInstant(this, "created_at")!!,
        handledAt = dialect.readInstant(this, "handled_at"),
    )
}
