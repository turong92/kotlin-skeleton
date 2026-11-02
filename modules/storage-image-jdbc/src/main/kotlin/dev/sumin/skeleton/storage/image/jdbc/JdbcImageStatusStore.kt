package dev.sumin.skeleton.storage.image.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.storage.image.ImageRow
import dev.sumin.skeleton.storage.image.ImageStatusStore
import dev.sumin.skeleton.storage.image.StoredVariant
import java.sql.ResultSet
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 이미지 상태 표 `storage_images` - 이미지 원본 하나당 한 줄.
 * 새 줄은 [SqlDialect.insertIgnore] 로 넣고(예외를 삼키는 방식은 PG 에서 트랜잭션을 abort 시킨다), **작업을 넣을 권리는 `queued=false -> true` 갱신이 1행일 때만** 갖는다 -
 * MySQL 의 insert-ignore 는 이미 있는 줄에도 1 을 돌려주므로 갱신 행 수로 「내가 만들었나」를 알 수 없기 때문이다. 같은 줄을 동시에 갱신하는 호출 중 하나만 1행을 본다.
 * 줄과 작업이 같은 트랜잭션이라 커밋되면 둘 다 있고 되돌아가면 둘 다 없다.
 */
class JdbcImageStatusStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
    private val time: TimeProvider,
) : ImageStatusStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun find(keyHash: String): ImageRow? =
        jdbc.query("select * from storage_images where key_hash = :h", mapOf("h" to keyHash)) { rs, _ -> rs.toRow() }.firstOrNull()

    override fun findAll(keyHashes: Collection<String>): Map<String, ImageRow> =
        keyHashes.distinct().chunked(500).flatMap { chunk ->
            jdbc.query("select * from storage_images where key_hash in (:hashes)", mapOf("hashes" to chunk)) { rs, _ -> rs.toRow() }
        }.associateBy { it.keyHash }

    override fun register(keyHash: String, objectKey: String, onCreated: () -> Unit): Boolean = transactions.execute {
        val now = dialect.instantParam(time.now())
        jdbc.update(
            dialect.insertIgnore("storage_images", listOf("key_hash", "object_key", "status", "animated", "queued", "created_at", "updated_at"), listOf("key_hash")),
            MapSqlParameterSource().addValue("key_hash", keyHash).addValue("object_key", objectKey).addValue("status", UploadImageState.PENDING.name)
                .addValue("animated", false).addValue("queued", false).addValue("created_at", now).addValue("updated_at", now),
        )
        val claimed = jdbc.update("update storage_images set queued = true where key_hash = :h and queued = false", mapOf("h" to keyHash)) == 1
        if (claimed) onCreated()
        claimed
    }!!

    override fun markReady(keyHash: String, width: Int, height: Int, format: String, animated: Boolean, variants: Map<String, StoredVariant>): Boolean =
        jdbc.update(
            "update storage_images set status = 'READY', width = :w, height = :ht, format = :f, animated = :a, variants = :v, reason = null, updated_at = :now where key_hash = :h and status = 'PENDING'",
            MapSqlParameterSource().addValue("w", width).addValue("ht", height).addValue("f", format).addValue("a", animated)
                .addValue("v", variants.toJson()).addValue("now", dialect.instantParam(time.now())).addValue("h", keyHash),
        ) == 1

    override fun markTerminal(keyHash: String, state: UploadImageState, reason: String, width: Int?, height: Int?): Boolean {
        require(state != UploadImageState.PENDING && state != UploadImageState.READY) { "not a terminal state: $state" }
        return jdbc.update(
            "update storage_images set status = :s, reason = :r, width = :w, height = :ht, updated_at = :now where key_hash = :h and status = 'PENDING'",
            MapSqlParameterSource().addValue("s", state.name).addValue("r", reason).addValue("w", width, java.sql.Types.INTEGER).addValue("ht", height, java.sql.Types.INTEGER)
                .addValue("now", dialect.instantParam(time.now())).addValue("h", keyHash),
        ) == 1
    }

    override fun delete(keyHashes: Collection<String>) {
        keyHashes.distinct().chunked(500).forEach { jdbc.update("delete from storage_images where key_hash in (:hashes)", mapOf("hashes" to it)) }
    }

    private fun ResultSet.toRow(): ImageRow = ImageRow(
        keyHash = getString("key_hash"),
        objectKey = getString("object_key"),
        state = UploadImageState.valueOf(getString("status")),
        format = getString("format"),
        width = getInt("width").takeUnless { wasNull() },
        height = getInt("height").takeUnless { wasNull() },
        animated = getBoolean("animated"),
        variants = variantsOf(getString("variants")),
        reason = getString("reason"),
    )

    companion object {
        private val ENTRY = Regex(""""([a-z]+)":\{"w":(\d+),"h":(\d+),"ext":"([a-z-]+)"(?:,"alt":"([a-z,]+)")?}""")

        /** 닫힌 모양 `{"thumb":{"w":400,"h":300,"ext":"jpg","alt":"webp"},…}`(`alt` 는 다른 형식이 있을 때만, 쉼표로 이음) - 이름은 소문자, 확장자는 소문자 또는 `-` 뿐이라 이스케이프가 필요 없다 */
        internal fun Map<String, StoredVariant>.toJson(): String? =
            takeIf { it.isNotEmpty() }?.entries?.joinToString(",", "{", "}") { (name, v) ->
                require(name.all { it in 'a'..'z' } && v.ext.all { it in 'a'..'z' || it == '-' } && v.alt.all { a -> a.isNotEmpty() && a.all { it in 'a'..'z' } }) { "unexpected variant name or extension" }
                val alt = if (v.alt.isEmpty()) "" else ""","alt":"${v.alt.joinToString(",")}""""
                """"$name":{"w":${v.width},"h":${v.height},"ext":"${v.ext}"$alt}"""
            }

        internal fun variantsOf(json: String?): Map<String, StoredVariant> =
            json?.let { ENTRY.findAll(it).associate { m -> m.groupValues[1] to StoredVariant(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4], m.groupValues[5].split(',').filter { a -> a.isNotEmpty() }) } }.orEmpty()
    }
}
