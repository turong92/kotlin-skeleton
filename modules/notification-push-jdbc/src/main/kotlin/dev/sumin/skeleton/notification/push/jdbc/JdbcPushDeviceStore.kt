package dev.sumin.skeleton.notification.push.jdbc

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushDeviceRegistration
import dev.sumin.skeleton.notification.push.PushDeviceStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 표 `push_devices` - 한 행 = 한 설치(`installation_id` 유니크), 주소는 한 줄에만(`address_hash` 유니크).
 *
 * 등록은 한 트랜잭션으로 「같은 주소의 **다른 설치** 줄 지우기 → 넣기(어느 유니크 충돌이든 무시: [SqlDialect.insertIgnore]) → 갱신 → 계정 상한 넘는 오래된 줄 지우기」.
 * 같은 설치의 동시 `PUT` 둘은 한 줄로 끝난다. 같은 주소를 서로 다른 설치가 동시에 등록하는 드문 경합(유니크 위반 · 교착)은 트랜잭션 밖에서 몇 번 다시 시도한다.
 * 상한은 순차 등록에서 정확하다 - 같은 계정의 동시 등록은 일시적으로 상한을 넘을 수 있고 다음 등록이 다시 줄인다.
 * 지우기는 모두 **계정을 같이 본다** - 남의 설치 id 로는 아무것도 지워지지 않는다.
 */
class JdbcPushDeviceStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
) : PushDeviceStore {
    private val tx = TransactionTemplate(transactionManager)

    private val rowMapper = RowMapper { rs, _ ->
        PushDevice(
            id = rs.getString("id"), accountId = rs.getString("account_id"), installationId = rs.getString("installation_id"),
            sessionId = rs.getString("session_id"), kind = rs.getString("kind"), platform = rs.getString("platform"),
            address = rs.getString("address"), p256dh = rs.getString("p256dh"), authSecret = rs.getString("auth_secret"),
            keyId = rs.getString("vapid_key_id"), label = rs.getString("label"),
            createdAt = dialect.readInstant(rs, "created_at")!!, lastSeenAt = dialect.readInstant(rs, "last_seen_at")!!,
            lastSuccessAt = dialect.readInstant(rs, "last_success_at"),
        )
    }

    override fun register(request: PushDeviceRegistration): PushDevice {
        var attempt = 0
        while (true) {
            try {
                return tx.execute { upsert(request) }!!
            } catch (e: DuplicateKeyException) {
                if (++attempt >= MAX_ATTEMPTS) throw e
            } catch (e: ConcurrencyFailureException) {
                if (++attempt >= MAX_ATTEMPTS) throw e
            }
        }
    }

    private fun upsert(request: PushDeviceRegistration): PushDevice {
        val r = request.registration
        val hash = addressHash(r.kind, r.address)
        jdbc.update(
            "delete from push_devices where address_hash = :hash and installation_id <> :installationId",
            MapSqlParameterSource().addValue("hash", hash).addValue("installationId", request.installationId),
        )
        val now = dialect.instantParam(request.now)
        val values = MapSqlParameterSource()
            .addValue("id", SortableIds.system.newId("pd_"))
            .addValue("account_id", request.accountId)
            .addValue("installation_id", request.installationId)
            .addValue("session_id", request.sessionId)
            .addValue("kind", r.kind)
            .addValue("platform", r.platform)
            .addValue("address", r.address)
            .addValue("address_hash", hash)
            .addValue("p256dh", r.p256dh)
            .addValue("auth_secret", r.authSecret)
            .addValue("vapid_key_id", request.keyId)
            .addValue("label", r.label)
            .addValue("created_at", now)
            .addValue("last_seen_at", now)
        jdbc.update(dialect.insertIgnore(TABLE, INSERT_COLUMNS, emptyList()), values)
        jdbc.update(
            """
            update push_devices set account_id = :account_id, session_id = :session_id, kind = :kind, platform = :platform,
                address = :address, address_hash = :address_hash, p256dh = :p256dh, auth_secret = :auth_secret,
                vapid_key_id = :vapid_key_id, label = :label, last_seen_at = :last_seen_at
            where installation_id = :installation_id
            """.trimIndent(),
            values,
        )
        // 없으면 같은 주소를 다른 설치가 동시에 먼저 넣어 이쪽 넣기가 무시된 것 - 처음부터 다시
        val row = jdbc.query("select * from push_devices where installation_id = :i", mapOf("i" to request.installationId), rowMapper).firstOrNull()
            ?: throw ConcurrencyFailureException("push device registration lost a race on the same address")
        trim(request.accountId, row.id, request.maxDevicesPerAccount)
        return row
    }

    /** 계정 기기가 [max] 를 넘으면 `last_seen_at` 이 가장 오랜 것부터 지운다 (방금 등록한 [keepId] 는 남긴다) */
    private fun trim(accountId: String, keepId: String, max: Int) {
        val total = jdbc.queryForObject("select count(*) from push_devices where account_id = :a", mapOf("a" to accountId), Long::class.java) ?: 0L
        val excess = total - max
        if (excess <= 0) return
        val doomed = jdbc.queryForList(
            "select id from push_devices where account_id = :a and id <> :keep order by last_seen_at asc, id asc limit :n",
            mapOf("a" to accountId, "keep" to keepId, "n" to excess.toInt()),
            String::class.java,
        )
        if (doomed.isNotEmpty()) jdbc.update("delete from push_devices where id in (:ids)", mapOf("ids" to doomed))
    }

    override fun find(id: String): PushDevice? =
        jdbc.query("select * from push_devices where id = :id", mapOf("id" to id), rowMapper).firstOrNull()

    override fun listByAccount(accountId: String): List<PushDevice> =
        jdbc.query("select * from push_devices where account_id = :a order by last_seen_at desc, id desc", mapOf("a" to accountId), rowMapper)

    override fun listByAccounts(accountIds: Set<String>): List<PushDevice> =
        accountIds.toList().chunked(IN_LIST_CHUNK).flatMap { chunk ->
            jdbc.query("select * from push_devices where account_id in (:ids) order by id", mapOf("ids" to chunk), rowMapper)
        }

    override fun findByInstallation(accountId: String, installationId: String): PushDevice? =
        jdbc.query(
            "select * from push_devices where account_id = :a and installation_id = :i",
            mapOf("a" to accountId, "i" to installationId), rowMapper,
        ).firstOrNull()

    override fun deleteByInstallation(accountId: String, installationId: String): Boolean =
        jdbc.update("delete from push_devices where account_id = :a and installation_id = :i", mapOf("a" to accountId, "i" to installationId)) > 0

    override fun delete(id: String): Boolean = jdbc.update("delete from push_devices where id = :id", mapOf("id" to id)) > 0

    override fun deleteBySession(accountId: String, sessionId: String): Int =
        jdbc.update("delete from push_devices where account_id = :a and session_id = :s", mapOf("a" to accountId, "s" to sessionId))

    override fun deleteByAccount(accountId: String): Int =
        jdbc.update("delete from push_devices where account_id = :a", mapOf("a" to accountId))

    /** 1000줄씩 나눠 지운다(알림 보관 정리와 같다) */
    override fun deleteStale(before: Instant): Int {
        var total = 0
        while (true) {
            val ids = jdbc.queryForList(
                "select id from push_devices where last_seen_at < :before order by last_seen_at limit :n",
                MapSqlParameterSource().addValue("before", dialect.instantParam(before)).addValue("n", DELETE_CHUNK),
                String::class.java,
            )
            if (ids.isEmpty()) return total
            total += jdbc.update("delete from push_devices where id in (:ids)", mapOf("ids" to ids))
            if (ids.size < DELETE_CHUNK) return total
        }
    }

    override fun markSuccess(id: String, at: Instant) {
        jdbc.update(
            "update push_devices set last_success_at = :at where id = :id",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("id", id),
        )
    }

    private fun addressHash(kind: String, address: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$kind\n$address".toByteArray(Charsets.UTF_8)))

    private companion object {
        const val TABLE = "push_devices"
        val INSERT_COLUMNS = listOf(
            "id", "account_id", "installation_id", "session_id", "kind", "platform", "address", "address_hash",
            "p256dh", "auth_secret", "vapid_key_id", "label", "created_at", "last_seen_at",
        )
        const val MAX_ATTEMPTS = 3
        const val IN_LIST_CHUNK = 500
        const val DELETE_CHUNK = 1000
    }
}
