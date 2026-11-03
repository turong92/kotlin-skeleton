package dev.sumin.skeleton.waitingroom.jdbc

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.waitingroom.NewWaitingRoom
import dev.sumin.skeleton.waitingroom.RateSegment
import dev.sumin.skeleton.waitingroom.RoomExistsException
import dev.sumin.skeleton.waitingroom.RoomStatus
import dev.sumin.skeleton.waitingroom.WaitingRoom
import dev.sumin.skeleton.waitingroom.WaitingRoomStore
import dev.sumin.skeleton.waitingroom.WaitingTicket
import java.security.SecureRandom
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.transaction.support.TransactionTemplate

/**
 * 대기열 저장소 — 두 방언이 같은 SQL 이다. 규칙:
 * - **번호는 방 줄(`waiting_rooms`)을 먼저 잠그고 준다**: `queue_tail = queue_tail + 1` 조건 없는 갱신이 방 줄 잠금이고, 같은 트랜잭션에서 읽은 값이 내 번호다.
 *   순번표 넣기가 유니크(방, 계정)에 걸리면 트랜잭션이 통째로 되돌려져 번호도 되돌아간다 → 번호는 1..n 빈틈이 없다. 방 줄 잠금이 늘 첫째라 교착이 없다.
 * - **섞기는 조건부 갱신**(`shuffled_at is null`)을 이긴 한 번뿐이다. 진 호출은 이긴 쪽 트랜잭션(번호 매기기까지)이 끝나야 그 갱신에서 풀려나므로, false 로 돌아왔을 때는 번호가 다 매겨져 있다.
 *   섞기 읽기와 겹쳐 커밋된 늦은 오픈 전 줄(번호 없음)은 [renumber] 가 맨 뒤 번호로 거둔다.
 * - 시각은 서비스의 `TimeProvider` 한 곳에서 온다(저장소는 시계를 보지 않는다).
 */
class JdbcWaitingRoomStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
    private val ids: SortableIds = SortableIds.system,
    private val random: SecureRandom = SecureRandom(),
) : WaitingRoomStore {
    override fun createRoom(room: NewWaitingRoom, now: Instant): WaitingRoom {
        val id = ids.newId("wr_")
        try {
            tx.executeWithoutResult {
                jdbc.update(
                    "insert into waiting_rooms (id, scope_key, opens_at, prequeue_starts_at, entry_seconds, status, shuffled_at, queue_tail, created_at) " +
                        "values (:id, :scope, :opens, :prequeue, :entry, :status, null, 0, :now)",
                    mapOf(
                        "id" to id, "scope" to room.scopeKey, "opens" to ts(room.opensAt), "prequeue" to ts(room.opensAt.minus(Duration.ofMinutes(room.prequeueMinutes.toLong()))),
                        "entry" to Duration.ofMinutes(room.entryMinutes.toLong()).seconds, "status" to RoomStatus.ACTIVE.name, "now" to ts(now),
                    ),
                )
                insertRate(id, room.opensAt, room.perMinute)
            }
        } catch (_: DuplicateKeyException) {
            throw RoomExistsException()
        }
        return checkNotNull(findRoom(id))
    }

    override fun findRoom(id: String): WaitingRoom? =
        jdbc.query("select * from waiting_rooms where id = :id", mapOf("id" to id)) { rs, _ -> rs.toRoom() }.firstOrNull()

    override fun findRoomByScope(scopeKey: String): WaitingRoom? =
        jdbc.query("select * from waiting_rooms where scope_key = :scope", mapOf("scope" to scopeKey)) { rs, _ -> rs.toRoom() }.firstOrNull()

    override fun rates(roomId: String): List<RateSegment> =
        jdbc.query("select from_at, per_minute from waiting_room_rates where room_id = :id order by from_at", mapOf("id" to roomId)) { rs, _ ->
            RateSegment(dialect.readInstant(rs, "from_at")!!, rs.getInt("per_minute"))
        }

    override fun setRate(roomId: String, perMinute: Int, now: Instant) {
        tx.executeWithoutResult {
            jdbc.update("delete from waiting_room_rates where room_id = :id and from_at = :at", mapOf("id" to roomId, "at" to ts(now)))
            insertRate(roomId, now, perMinute)
        }
    }

    override fun close(roomId: String) {
        jdbc.update("update waiting_rooms set status = :status where id = :id", mapOf("id" to roomId, "status" to RoomStatus.CLOSED.name))
    }

    override fun shuffle(roomId: String, now: Instant): Boolean = tx.execute {
        val won = jdbc.update("update waiting_rooms set shuffled_at = :now where id = :id and shuffled_at is null", mapOf("id" to roomId, "now" to ts(now))) == 1
        if (!won) return@execute false
        var number = jdbc.queryForObject("select queue_tail from waiting_rooms where id = :id", mapOf("id" to roomId), Long::class.java)!!
        val waiting = jdbc.queryForList("select id from waiting_room_tickets where room_id = :id and queue_no is null order by shuffle_key, id", mapOf("id" to roomId), String::class.java)
        waiting.chunked(BATCH).forEach { chunk ->
            val params: List<SqlParameterSource> = chunk.map { MapSqlParameterSource().addValue("id", it).addValue("no", ++number) }
            jdbc.batchUpdate("update waiting_room_tickets set queue_no = :no where id = :id and queue_no is null", params.toTypedArray())
        }
        jdbc.update("update waiting_rooms set queue_tail = :tail where id = :id", mapOf("id" to roomId, "tail" to number))
        true
    }!!

    override fun findTicket(roomId: String, accountId: String): WaitingTicket? =
        jdbc.query("select * from waiting_room_tickets where room_id = :room and account_id = :account", mapOf("room" to roomId, "account" to accountId)) { rs, _ -> rs.toTicket() }.firstOrNull()

    override fun addPrequeueTicket(roomId: String, accountId: String, now: Instant): WaitingTicket {
        val ticket = WaitingTicket(ids.newId("wrt_"), roomId, accountId, null, now)
        return try {
            insertTicket(ticket)
            ticket
        } catch (_: DuplicateKeyException) {
            checkNotNull(findTicket(roomId, accountId))
        }
    }

    override fun addTailTicket(roomId: String, accountId: String, now: Instant, floor: Long): WaitingTicket {
        val id = ids.newId("wrt_")
        return try {
            tx.execute {
                val number = nextNumber(roomId, floor)
                WaitingTicket(id, roomId, accountId, number, now).also { insertTicket(it) }
            }!!
        } catch (_: DuplicateKeyException) {
            checkNotNull(findTicket(roomId, accountId))   // 같은 계정이 먼저 섰다 - 번호도 되돌아갔다
        }
    }

    override fun renumber(ticket: WaitingTicket, expected: Long?, now: Instant, floor: Long): WaitingTicket {
        val number = tx.execute { status ->
            val next = nextNumber(ticket.roomId, floor)
            val updated = jdbc.update(
                "update waiting_room_tickets set queue_no = :no, created_at = :now where id = :id and " + if (expected == null) "queue_no is null" else "queue_no = :expected",
                mapOf("no" to next, "now" to ts(now), "id" to ticket.id, "expected" to expected),
            )
            if (updated == 1) {
                next
            } else {
                status.setRollbackOnly()   // 다른 호출이 먼저 바꿨다 - 방금 올린 번호를 되돌린다
                null
            }
        }
        return if (number != null) ticket.copy(queueNo = number, createdAt = now) else checkNotNull(findTicket(ticket.roomId, ticket.accountId))
    }

    override fun countPrequeue(roomId: String): Long =
        jdbc.queryForObject("select count(*) from waiting_room_tickets where room_id = :id and queue_no is null", mapOf("id" to roomId), Long::class.java)!!

    /** 방 줄을 잠그고 다음 번호 `max(끝, floor) + 1` 를 읽는다 - 서빙이 끝을 앞질렀으면(줄이 한산했으면) 서빙 바로 다음 번호 - 트랜잭션 안에서만 */
    private fun nextNumber(roomId: String, floor: Long): Long {
        jdbc.update(
            "update waiting_rooms set queue_tail = case when queue_tail < :floor then :floor + 1 else queue_tail + 1 end where id = :id",
            mapOf("id" to roomId, "floor" to floor),
        )
        return jdbc.queryForObject("select queue_tail from waiting_rooms where id = :id", mapOf("id" to roomId), Long::class.java)!!
    }

    private fun insertTicket(ticket: WaitingTicket) {
        jdbc.update(
            "insert into waiting_room_tickets (id, room_id, account_id, queue_no, shuffle_key, created_at) values (:id, :room, :account, :no, :key, :now)",
            mapOf("id" to ticket.id, "room" to ticket.roomId, "account" to ticket.accountId, "no" to ticket.queueNo, "key" to random.nextLong(), "now" to ts(ticket.createdAt)),
        )
    }

    private fun insertRate(roomId: String, from: Instant, perMinute: Int) {
        jdbc.update("insert into waiting_room_rates (room_id, from_at, per_minute) values (:id, :at, :rate)", mapOf("id" to roomId, "at" to ts(from), "rate" to perMinute))
    }

    private fun ts(value: Instant?): Any? = dialect.instantParam(value)

    private fun ResultSet.toRoom() = WaitingRoom(
        id = getString("id"),
        scopeKey = getString("scope_key"),
        opensAt = dialect.readInstant(this, "opens_at")!!,
        prequeueStartsAt = dialect.readInstant(this, "prequeue_starts_at")!!,
        entryTtl = Duration.ofSeconds(getLong("entry_seconds")),
        status = RoomStatus.valueOf(getString("status")),
        shuffledAt = dialect.readInstant(this, "shuffled_at"),
        queueTail = getLong("queue_tail"),
        createdAt = dialect.readInstant(this, "created_at")!!,
    )

    private fun ResultSet.toTicket() = WaitingTicket(
        id = getString("id"),
        roomId = getString("room_id"),
        accountId = getString("account_id"),
        queueNo = getLong("queue_no").takeUnless { wasNull() },
        createdAt = dialect.readInstant(this, "created_at")!!,
    )

    private companion object {
        const val BATCH = 1000
    }
}
