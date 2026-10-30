package dev.sumin.skeleton.review.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.review.ReportReason
import dev.sumin.skeleton.review.Review
import dev.sumin.skeleton.review.ReviewFilter
import dev.sumin.skeleton.review.ReviewPurchase
import dev.sumin.skeleton.review.ReviewQueue
import dev.sumin.skeleton.review.ReviewReply
import dev.sumin.skeleton.review.ReviewRows
import dev.sumin.skeleton.review.ReviewSort
import dev.sumin.skeleton.review.ReviewStatus
import dev.sumin.skeleton.review.ReviewStore
import dev.sumin.skeleton.review.ReviewTarget
import dev.sumin.skeleton.review.TargetStats
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 리뷰 표 일곱(`reviews` · `review_target_stats` · `review_helpful` · `review_purchases` · `review_reports` · `review_replies` · `review_photos`).
 * **잠금 순서는 리뷰 줄 → 집계 줄.** 새 줄은 [SqlDialect.insertIgnore] 로 먼저 넣고(PG 에서 유니크 위반 예외는 호출자 트랜잭션 전체를 abort 시키고,
 * 없는 키를 `for update` 로 잠그면 MySQL 의 틈 잠금 때문에 동시에 온 두 호출이 교착한다) **넣었는지는 내가 만든 id 가 있는지로 본다**
 * (MySQL 의 `on duplicate key update` 는 이미 있는 행에도 1 을 돌려준다). 집계는 `set n = n + :d` 원자적 갱신이라 동시에 와도 어긋나지 않는다.
 * 남에게 보이는 목록 질의는 `status = 'PUBLISHED'`(보는 사람 자신의 `HIDDEN` 만 더)인 줄만 읽는다 - 걸러 내는 것이 아니라 읽지 않는다.
 */
class JdbcReviewStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
) : ReviewStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }

    // 리뷰 줄

    override fun find(id: String): Review? =
        jdbc.query("select * from reviews where id = :id", mapOf("id" to id)) { rs, _ -> rs.toReview() }.firstOrNull()

    override fun findByTarget(target: ReviewTarget, accountId: String): Review? =
        jdbc.query("select * from reviews where target_type = :t and target_id = :i and account_id = :a", target.params(accountId)) { rs, _ -> rs.toReview() }.firstOrNull()

    override fun lock(id: String): Review? =
        jdbc.query("select * from reviews where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.toReview() }.firstOrNull()

    override fun lockByTarget(target: ReviewTarget, accountId: String): Review? =
        jdbc.query("select * from reviews where target_type = :t and target_id = :i and account_id = :a for update", target.params(accountId)) { rs, _ -> rs.toReview() }.firstOrNull()

    override fun insertIfAbsent(review: Review): Boolean {
        jdbc.update(
            dialect.insertIgnore("reviews", REVIEW_COLUMNS, listOf("target_type", "target_id", "account_id")),
            MapSqlParameterSource()
                .addValue("id", review.id)
                .addValue("target_type", review.targetType)
                .addValue("target_id", review.targetId)
                .addValue("account_id", review.accountId)
                .addValue("rating", review.rating)
                .addValue("body", review.body)
                .addValue("status", review.status.name)
                .addValue("verified", review.verified)
                .addValue("helpful_count", review.helpfulCount)
                .addValue("report_count", review.reportCount)
                .addValue("reported_at", null, Types.TIMESTAMP)
                .addValue("reports_reviewed_at", null, Types.TIMESTAMP)
                .addValue("moderated_by", review.moderatedBy, Types.VARCHAR)
                .addValue("moderation_reason", review.moderationReason, Types.VARCHAR)
                .addValue("photo_count", review.photoCount)
                .addValue("created_at", instant(review.createdAt))
                .addValue("updated_at", instant(review.updatedAt))
                .addValue("published_at", instant(review.publishedAt)),
        )
        return jdbc.queryForObject("select count(*) from reviews where id = :id", mapOf("id" to review.id), Long::class.java) == 1L
    }

    override fun revive(id: String, rating: Int, body: String, verified: Boolean, at: Instant): Boolean {
        val changed = jdbc.update(
            """update reviews set status = 'PUBLISHED', rating = :rating, body = :body, verified = :verified, helpful_count = 0, report_count = 0,
               reported_at = null, reports_reviewed_at = null, photo_count = 0, created_at = :at, updated_at = :at, published_at = :at
               where id = :id and status = 'DELETED'""",
            MapSqlParameterSource().addValue("id", id).addValue("rating", rating).addValue("body", body).addValue("verified", verified).addValue("at", instant(at)),
        ) == 1
        if (changed) {
            // 옛 내용에 달렸던 것은 새 내용과 상관없다
            for (table in listOf("review_helpful", "review_reports")) jdbc.update("delete from $table where review_id = :id", mapOf("id" to id))
            jdbc.update("delete from review_replies where review_id = :id", mapOf("id" to id))
            jdbc.update("delete from review_photos where review_id = :id", mapOf("id" to id))
        }
        return changed
    }

    override fun updateContent(id: String, rating: Int, body: String, at: Instant): Boolean =
        jdbc.update("update reviews set rating = :rating, body = :body, updated_at = :at where id = :id", mapOf("id" to id, "rating" to rating, "body" to body, "at" to instant(at))) == 1

    override fun markDeleted(id: String, at: Instant): Boolean =
        jdbc.update("update reviews set status = 'DELETED', updated_at = :at where id = :id and status <> 'DELETED'", mapOf("id" to id, "at" to instant(at))) == 1

    override fun page(target: ReviewTarget, viewer: String?, filter: ReviewFilter, sort: ReviewSort, offset: Int, limit: Int): ReviewRows {
        val params = MapSqlParameterSource().addValue("t", target.type).addValue("i", target.id)
        val where = StringBuilder("target_type = :t and target_id = :i and ")
        if (viewer == null) {
            where.append("status = 'PUBLISHED'")
        } else {
            where.append("(status = 'PUBLISHED' or (status = 'HIDDEN' and account_id = :viewer))")
            params.addValue("viewer", viewer)
        }
        filter.rating?.let { where.append(" and rating = :rating"); params.addValue("rating", it) }
        if (filter.verified) where.append(" and verified = true")
        if (filter.photo) where.append(" and photo_count > 0")
        val order = when (sort) {
            ReviewSort.RECENT -> "published_at desc, id desc"
            ReviewSort.HELPFUL -> "helpful_count desc, published_at desc, id desc"
            ReviewSort.RATING_HIGH -> "rating desc, published_at desc, id desc"
            ReviewSort.RATING_LOW -> "rating asc, published_at desc, id desc"
        }
        val total = jdbc.queryForObject("select count(*) from reviews where $where", params, Long::class.java) ?: 0L
        val rows = jdbc.query("select * from reviews where $where order by $order limit :limit offset :offset", params.addValue("limit", limit).addValue("offset", offset)) { rs, _ -> rs.toReview() }
        return ReviewRows(rows, total)
    }

    override fun pageMine(accountId: String, before: String?, limit: Int): List<Review> {
        val params = MapSqlParameterSource().addValue("a", accountId).addValue("limit", limit)
        val cursor = if (before != null) " and id < :before".also { params.addValue("before", before) } else ""
        return jdbc.query("select * from reviews where account_id = :a and status <> 'DELETED'$cursor order by id desc limit :limit", params) { rs, _ -> rs.toReview() }
    }

    // 신고 · 운영

    override fun hasReport(reviewId: String, accountId: String): Boolean =
        jdbc.queryForObject("select count(*) from review_reports where review_id = :r and account_id = :a", mapOf("r" to reviewId, "a" to accountId), Long::class.java) == 1L

    override fun insertReport(reviewId: String, accountId: String, reason: ReportReason, detail: String?, at: Instant) {
        jdbc.update(
            dialect.insertIgnore("review_reports", listOf("review_id", "account_id", "reason", "detail", "created_at"), listOf("review_id", "account_id")),
            MapSqlParameterSource().addValue("review_id", reviewId).addValue("account_id", accountId).addValue("reason", reason.name)
                .addValue("detail", detail, Types.VARCHAR).addValue("created_at", instant(at)),
        )
    }

    override fun addReportCount(reviewId: String, at: Instant): Int {
        jdbc.update("update reviews set report_count = report_count + 1, reported_at = :at where id = :id", mapOf("id" to reviewId, "at" to instant(at)))
        return jdbc.queryForObject("select report_count from reviews where id = :id", mapOf("id" to reviewId), Int::class.java) ?: 0
    }

    override fun hide(id: String, by: String, reason: String, at: Instant): Boolean =
        jdbc.update(
            "update reviews set status = 'HIDDEN', moderated_by = :by, moderation_reason = :reason, reports_reviewed_at = :at, updated_at = :at where id = :id and status = 'PUBLISHED'",
            mapOf("id" to id, "by" to by, "reason" to reason, "at" to instant(at)),
        ) == 1

    override fun restore(id: String, at: Instant): Boolean =
        jdbc.update(
            "update reviews set status = 'PUBLISHED', moderated_by = null, moderation_reason = null, updated_at = :at where id = :id and status = 'HIDDEN'",
            mapOf("id" to id, "at" to instant(at)),
        ) == 1

    override fun clearModeration(id: String, at: Instant): Boolean =
        jdbc.update(
            "update reviews set moderated_by = null, moderation_reason = null, updated_at = :at where id = :id and status = 'DELETED' and moderated_by is not null",
            mapOf("id" to id, "at" to instant(at)),
        ) == 1

    override fun dismissReports(id: String, at: Instant): Boolean =
        jdbc.update("update reviews set reports_reviewed_at = :at where id = :id and status <> 'DELETED'", mapOf("id" to id, "at" to instant(at))) == 1

    override fun pageAdmin(queue: ReviewQueue, targetType: String?, before: String?, limit: Int): List<Review> {
        val params = MapSqlParameterSource().addValue("limit", limit)
        val where = StringBuilder(
            when (queue) {
                ReviewQueue.REPORTED -> "status = 'PUBLISHED' and report_count > 0 and (reports_reviewed_at is null or reported_at > reports_reviewed_at)"
                ReviewQueue.HIDDEN -> "(status = 'HIDDEN' or (status = 'DELETED' and moderated_by is not null))"
                ReviewQueue.ALL -> "status <> 'DELETED'"
            },
        )
        targetType?.let { where.append(" and target_type = :type"); params.addValue("type", it) }
        before?.let { where.append(" and id < :before"); params.addValue("before", it) }
        return jdbc.query("select * from reviews where $where order by id desc limit :limit", params) { rs, _ -> rs.toReview() }
    }

    override fun reportReasonCounts(reviewIds: Collection<String>): Map<String, Map<String, Int>> {
        if (reviewIds.isEmpty()) return emptyMap()
        val counts = HashMap<String, MutableMap<String, Int>>()
        jdbc.query("select review_id, reason, count(*) as n from review_reports where review_id in (:ids) group by review_id, reason", mapOf("ids" to reviewIds.toList())) { rs ->
            counts.getOrPut(rs.getString("review_id")) { LinkedHashMap() }[rs.getString("reason")] = rs.getInt("n")
        }
        return counts
    }

    // 답글

    override fun repliesOf(reviewIds: Collection<String>): Map<String, ReviewReply> {
        if (reviewIds.isEmpty()) return emptyMap()
        return jdbc.query("select * from review_replies where review_id in (:ids)", mapOf("ids" to reviewIds.toList())) { rs, _ ->
            rs.getString("review_id") to ReviewReply(rs.getString("body"), rs.getString("actor_id"), dialect.readInstant(rs, "created_at")!!, dialect.readInstant(rs, "updated_at")!!)
        }.toMap()
    }

    override fun saveReply(reviewId: String, body: String, actorId: String, at: Instant) {
        val changed = jdbc.update(
            "update review_replies set body = :body, actor_id = :actor, updated_at = :at where review_id = :r",
            mapOf("r" to reviewId, "body" to body, "actor" to actorId, "at" to instant(at)),
        )
        if (changed == 0) {
            jdbc.update(
                dialect.insertIgnore("review_replies", listOf("review_id", "body", "actor_id", "created_at", "updated_at"), listOf("review_id")),
                mapOf("review_id" to reviewId, "body" to body, "actor_id" to actorId, "created_at" to instant(at), "updated_at" to instant(at)),
            )
        }
    }

    override fun deleteReply(reviewId: String): Boolean = jdbc.update("delete from review_replies where review_id = :r", mapOf("r" to reviewId)) == 1

    // 사진

    override fun replacePhotos(reviewId: String, keys: List<String>) {
        jdbc.update("delete from review_photos where review_id = :r", mapOf("r" to reviewId))
        keys.forEachIndexed { index, key ->
            jdbc.update("insert into review_photos (review_id, idx, storage_key) values (:r, :i, :k)", mapOf("r" to reviewId, "i" to index, "k" to key))
        }
        jdbc.update("update reviews set photo_count = :n where id = :r", mapOf("r" to reviewId, "n" to keys.size))
    }

    override fun photoKey(reviewId: String, index: Int): String? =
        jdbc.queryForList("select storage_key from review_photos where review_id = :r and idx = :i", mapOf("r" to reviewId, "i" to index), String::class.java).firstOrNull()

    override fun countWritten(accountId: String): Int =
        jdbc.queryForObject("select count(*) from reviews where account_id = :a", mapOf("a" to accountId), Int::class.java) ?: 0

    // 집계

    override fun stats(target: ReviewTarget): TargetStats = statsOf(listOf(target))[target] ?: TargetStats.EMPTY

    override fun statsOf(targets: Collection<ReviewTarget>): Map<ReviewTarget, TargetStats> {
        val found = HashMap<ReviewTarget, TargetStats>()
        targets.distinct().chunked(STATS_CHUNK).forEach { chunk ->
            val params = MapSqlParameterSource()
            val clause = chunk.mapIndexed { n, t ->
                params.addValue("t$n", t.type).addValue("i$n", t.id)
                "(target_type = :t$n and target_id = :i$n)"
            }.joinToString(" or ")
            jdbc.query("select * from review_target_stats where $clause", params) { rs ->
                found[ReviewTarget(rs.getString("target_type"), rs.getString("target_id"))] =
                    TargetStats(rs.getInt("review_count"), rs.getInt("rating_sum"), (1..5).associateWith { rs.getInt("r$it") })
            }
        }
        return targets.associateWith { found[it] ?: TargetStats.EMPTY }
    }

    override fun adjustStats(target: ReviewTarget, rating: Int, delta: Int, at: Instant) {
        require(rating in 1..5)
        jdbc.update(
            dialect.insertIgnore("review_target_stats", STATS_COLUMNS, listOf("target_type", "target_id")),
            MapSqlParameterSource().addValue("target_type", target.type).addValue("target_id", target.id)
                .addValue("review_count", 0).addValue("rating_sum", 0).addValue("r1", 0).addValue("r2", 0).addValue("r3", 0).addValue("r4", 0).addValue("r5", 0)
                .addValue("updated_at", instant(at)),
        )
        jdbc.update(
            "update review_target_stats set review_count = review_count + :d, rating_sum = rating_sum + :s, r$rating = r$rating + :d, updated_at = :at where target_type = :t and target_id = :i",
            MapSqlParameterSource().addValue("d", delta).addValue("s", delta * rating).addValue("at", instant(at)).addValue("t", target.type).addValue("i", target.id),
        )
    }

    // 도움돼요

    override fun hasHelpful(reviewId: String, accountId: String): Boolean =
        jdbc.queryForObject("select count(*) from review_helpful where review_id = :r and account_id = :a", mapOf("r" to reviewId, "a" to accountId), Long::class.java) == 1L

    override fun helpfulOf(accountId: String, reviewIds: Collection<String>): Set<String> {
        if (reviewIds.isEmpty()) return emptySet()
        return jdbc.queryForList("select review_id from review_helpful where account_id = :a and review_id in (:ids)", mapOf("a" to accountId, "ids" to reviewIds.toList()), String::class.java).filterNotNull().toSet()
    }

    override fun insertHelpful(reviewId: String, accountId: String, at: Instant) {
        jdbc.update(
            dialect.insertIgnore("review_helpful", listOf("review_id", "account_id", "created_at"), listOf("review_id", "account_id")),
            mapOf("review_id" to reviewId, "account_id" to accountId, "created_at" to instant(at)),
        )
    }

    override fun deleteHelpful(reviewId: String, accountId: String): Boolean =
        jdbc.update("delete from review_helpful where review_id = :r and account_id = :a", mapOf("r" to reviewId, "a" to accountId)) == 1

    override fun adjustHelpful(reviewId: String, delta: Int): Int {
        jdbc.update("update reviews set helpful_count = helpful_count + :d where id = :id", mapOf("d" to delta, "id" to reviewId))
        return jdbc.queryForObject("select helpful_count from reviews where id = :id", mapOf("id" to reviewId), Int::class.java) ?: 0
    }

    // 구매 신호

    override fun hasLivePurchase(accountId: String, target: ReviewTarget): Boolean =
        jdbc.queryForObject(
            "select count(*) from review_purchases where account_id = :a and target_type = :t and target_id = :i and revoked_at is null",
            target.params(accountId), Long::class.java,
        )!! > 0

    override fun lockPurchase(ref: String): ReviewPurchase? =
        jdbc.query("select * from review_purchases where ref = :ref for update", mapOf("ref" to ref)) { rs, _ ->
            ReviewPurchase(rs.getString("id"), rs.getString("account_id"), rs.getString("target_type"), rs.getString("target_id"), rs.getString("ref"), dialect.readInstant(rs, "created_at")!!, dialect.readInstant(rs, "revoked_at"))
        }.firstOrNull()

    override fun insertPurchaseIfAbsent(purchase: ReviewPurchase): Boolean {
        jdbc.update(
            dialect.insertIgnore("review_purchases", listOf("id", "account_id", "target_type", "target_id", "ref", "created_at", "revoked_at"), listOf("ref")),
            MapSqlParameterSource().addValue("id", purchase.id).addValue("account_id", purchase.accountId).addValue("target_type", purchase.targetType)
                .addValue("target_id", purchase.targetId).addValue("ref", purchase.ref).addValue("created_at", instant(purchase.createdAt)).addValue("revoked_at", instant(purchase.revokedAt)),
        )
        return jdbc.queryForObject("select count(*) from review_purchases where id = :id", mapOf("id" to purchase.id), Long::class.java) == 1L
    }

    override fun revokePurchase(ref: String, at: Instant): Boolean =
        jdbc.update("update review_purchases set revoked_at = :at where ref = :ref and revoked_at is null", mapOf("ref" to ref, "at" to instant(at))) == 1

    override fun setVerified(accountId: String, target: ReviewTarget, verified: Boolean): Int =
        jdbc.update(
            "update reviews set verified = :v where account_id = :a and target_type = :t and target_id = :i",
            target.params(accountId).addValue("v", verified),
        )

    // 탈퇴

    override fun eraseAccount(accountId: String, tombstone: String): Int {
        val params = mapOf("a" to accountId, "tomb" to tombstone)
        val reviews = jdbc.update("update reviews set account_id = :tomb where account_id = :a", params)
        jdbc.update("update review_helpful set account_id = :tomb where account_id = :a", params)
        jdbc.update("update review_reports set account_id = :tomb where account_id = :a", params)
        jdbc.update("delete from review_purchases where account_id = :a", mapOf("a" to accountId))
        return reviews
    }

    // 읽기

    private fun instant(value: Instant?): Any? = dialect.instantParam(value)

    private fun ReviewTarget.params(accountId: String) = MapSqlParameterSource().addValue("t", type).addValue("i", id).addValue("a", accountId)

    private fun ResultSet.toReview() = Review(
        id = getString("id"),
        targetType = getString("target_type"),
        targetId = getString("target_id"),
        accountId = getString("account_id"),
        rating = getInt("rating"),
        body = getString("body"),
        status = ReviewStatus.valueOf(getString("status")),
        verified = getBoolean("verified"),
        helpfulCount = getInt("helpful_count"),
        reportCount = getInt("report_count"),
        moderatedBy = getString("moderated_by"),
        moderationReason = getString("moderation_reason"),
        photoCount = getInt("photo_count"),
        createdAt = dialect.readInstant(this, "created_at")!!,
        updatedAt = dialect.readInstant(this, "updated_at")!!,
        publishedAt = dialect.readInstant(this, "published_at")!!,
        reportedAt = dialect.readInstant(this, "reported_at"),
        reportsReviewedAt = dialect.readInstant(this, "reports_reviewed_at"),
    )

    private companion object {
        const val STATS_CHUNK = 50
        val REVIEW_COLUMNS = listOf(
            "id", "target_type", "target_id", "account_id", "rating", "body", "status", "verified", "helpful_count", "report_count", "reported_at",
            "reports_reviewed_at", "moderated_by", "moderation_reason", "photo_count", "created_at", "updated_at", "published_at",
        )
        val STATS_COLUMNS = listOf("target_type", "target_id", "review_count", "rating_sum", "r1", "r2", "r3", "r4", "r5", "updated_at")
    }
}
