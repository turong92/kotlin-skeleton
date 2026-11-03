package dev.sumin.skeleton.waitingroom

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/** 인스턴스가 방 정보를 쥐는 시간 - 묻기가 대부분 DB 를 안 읽게 한다. 이 인스턴스의 운영 변경은 바로 비운다 */
private val ROOM_CACHE = Duration.ofSeconds(2)
private const val MAX_PER_MINUTE = 1_000_000
private const val MAX_SCOPE_KEY = 200

/**
 * 앱이 쓰는 서버 API - 대기실 만들기 · 순번표 받기 · 묻기 · 입장권 검사 · 운영.
 *
 * 줄: 오픈 전 줄(`prequeueStartsAt` ~ `opensAt`)은 번호 없이 서 있다가 **오픈 시각 뒤 첫 요청**이 무작위로 섞어 1..n 을 매기고(방마다 한 번), 그 뒤 온 사람은 온 순서로 맨 뒤 번호를 받는다.
 * 입장은 번호 <= 서빙([Serving])이면 되고, 입장 시각부터 `entryTtl` 동안만이다 - 지나면 EXPIRED, 다시 서면 맨 뒤.
 * **입장권 검사([requireEntry])는 DB 를 읽지 않는다** - 서명한 토큰만 본다.
 */
class WaitingRooms(
    private val store: WaitingRoomStore,
    private val tokens: WaitingRoomTokens,
    private val time: TimeProvider,
) {
    private class RoomView(val room: WaitingRoom, val rates: List<RateSegment>, val loadedAt: Instant)

    private val cache = ConcurrentHashMap<String, RoomView>()

    // ---- 운영 ------------------------------------------------------------------------------------------------------

    fun create(room: NewWaitingRoom): WaitingRoom {
        if (room.scopeKey.isBlank() || room.scopeKey.length > MAX_SCOPE_KEY) throw FieldValidationException("scopeKey", "Size", "scopeKey must be 1..$MAX_SCOPE_KEY characters")
        if (room.perMinute !in 1..MAX_PER_MINUTE) throw FieldValidationException("perMinute", "Range", "perMinute must be 1..$MAX_PER_MINUTE")
        if (room.prequeueMinutes < 0) throw FieldValidationException("prequeueMinutes", "Min", "prequeueMinutes must not be negative")
        if (room.entryMinutes < 1) throw FieldValidationException("entryMinutes", "Min", "entryMinutes must be at least 1")
        return store.createRoom(room, time.now())
    }

    fun get(id: String): WaitingRoom = view(id).room

    fun findByScope(scopeKey: String): WaitingRoom? = store.findRoomByScope(scopeKey)

    /** 지금부터(오픈 전이면 오픈부터) 분당 [perMinute] 명. 0 은 멈춤 */
    fun setRate(id: String, perMinute: Int) {
        if (perMinute !in 0..MAX_PER_MINUTE) throw FieldValidationException("perMinute", "Range", "perMinute must be 0..$MAX_PER_MINUTE")
        val room = get(id).also { if (it.status == RoomStatus.CLOSED) throw WaitingRoomClosedException() }
        store.setRate(room.id, perMinute, maxOf(time.now(), room.opensAt))
        cache.remove(id)
    }

    /** 닫으면 새 순번표를 안 주고 묻기는 CLOSED 로 답한다. 이미 낸 입장권은 자기 만료까지 통한다(입장권 검사는 DB 를 읽지 않는다) */
    fun close(id: String) {
        store.close(get(id).id)
        cache.remove(id)
    }

    fun stats(id: String): WaitingRoomStats {
        val view = view(id, fresh = true)
        val now = time.now()
        val prequeue = store.countPrequeue(id)
        val issued = view.room.queueTail
        val serving = minOf(Serving.served(view.room.opensAt, view.rates, now), issued)
        val total = issued + prequeue
        val drain = if (total <= Serving.served(view.room.opensAt, view.rates, now)) 0L else Serving.admitAt(view.room.opensAt, view.rates, total)?.let { secondsUntil(it, now) }
        return WaitingRoomStats(prequeue, issued, serving, currentRate(view, now), drain)
    }

    // ---- 사용자 ----------------------------------------------------------------------------------------------------

    /** 순번표 받기 - 같은 계정이면 같은 순번표. 입장 시간이 지난 순번표만 맨 뒤로 다시 선다 */
    fun issueTicket(roomId: String, accountId: String): QueueStatus {
        var view = view(roomId)
        if (view.room.status == RoomStatus.CLOSED) throw WaitingRoomClosedException()
        val now = time.now()
        if (now < view.room.prequeueStartsAt) throw WaitingRoomNotOpenException(view.room.opensAt, view.room.prequeueStartsAt)
        val open = now >= view.room.opensAt
        if (open) view = shuffled(view, now)

        var ticket = store.findTicket(roomId, accountId)
            ?: if (open) store.addTailTicket(roomId, accountId, now, Serving.served(view.room.opensAt, view.rates, now)) else store.addPrequeueTicket(roomId, accountId, now)
        val number = ticket.queueNo
        if (open && (number == null || expired(view, number, now))) ticket = store.renumber(ticket, number, now, Serving.served(view.room.opensAt, view.rates, now))
        return statusOf(view, accountId, ticket.queueNo, now, ticket = tokens.ticket(roomId, accountId, ticket.queueNo))
    }

    /** 묻기 - 순번표 토큰에 번호가 있으면 DB 를 읽지 않는다. 오픈 뒤 번호가 처음 알려지면 번호가 든 새 순번표(`ticket`)를 함께 준다 */
    fun status(roomId: String, accountId: String, ticketToken: String?): QueueStatus {
        val claims = tokens.read(ticketToken)
        if (claims == null || claims.kind != TokenKind.TICKET || claims.roomId != roomId || claims.accountId != accountId) throw TicketInvalidException()
        var view = view(roomId)
        val now = time.now()
        var number = claims.position
        var refreshed: String? = null
        if (number == null && now >= view.room.opensAt && view.room.status != RoomStatus.CLOSED) {
            view = shuffled(view, now)
            var ticket = store.findTicket(roomId, accountId) ?: throw TicketInvalidException()
            if (ticket.queueNo == null) ticket = store.renumber(ticket, null, now, Serving.served(view.room.opensAt, view.rates, now))
            number = ticket.queueNo
            refreshed = tokens.ticket(roomId, accountId, number)
        }
        return statusOf(view, accountId, number, now, ticket = refreshed)
    }

    /** 방의 입장권이 이 계정의 것이고 지나지 않았는가 - 아니면 [EntryRequiredException]. 토큰만 보며 DB 를 읽지 않는다 */
    fun requireEntry(roomId: String, accountId: String, entryToken: String?) {
        val claims = tokens.read(entryToken)
        val valid = claims != null && claims.kind == TokenKind.ENTRY && claims.roomId == roomId && claims.accountId == accountId &&
            claims.expiresAt != null && time.now() < claims.expiresAt
        if (!valid) throw EntryRequiredException()
    }

    // ---- 계산 ------------------------------------------------------------------------------------------------------

    private fun statusOf(view: RoomView, accountId: String, number: Long?, now: Instant, ticket: String?): QueueStatus {
        val room = view.room
        if (room.status == RoomStatus.CLOSED) return QueueStatus(QueueState.CLOSED, number, null, null, room.opensAt, MAX_POLL, ticket = ticket)
        if (number == null || now < room.opensAt) {
            return QueueStatus(QueueState.PREQUEUE, null, null, null, room.opensAt, pollAfter(ahead = null, untilOpen = Duration.between(now, room.opensAt)), ticket = ticket)
        }
        val served = Serving.served(room.opensAt, view.rates, now)
        val admitAt = Serving.admitAt(room.opensAt, view.rates, number)
        if (number > served) {
            val ahead = number - served - 1
            return QueueStatus(QueueState.WAITING, number, ahead, admitAt?.let { secondsUntil(it, now) }, room.opensAt, pollAfter(ahead, null), ticket = ticket)
        }
        val expiresAt = checkNotNull(admitAt) { "a served number has an admission moment" }.plus(room.entryTtl)
        if (now >= expiresAt) return QueueStatus(QueueState.EXPIRED, number, 0, null, room.opensAt, MAX_POLL, ticket = ticket)
        return QueueStatus(QueueState.ADMITTED, number, 0, 0, room.opensAt, MAX_POLL, entryToken = tokens.entry(room.id, accountId, expiresAt), ticket = ticket)
    }

    /** 입장 시간이 지난 번호인가 */
    private fun expired(view: RoomView, number: Long, now: Instant): Boolean {
        if (number > Serving.served(view.room.opensAt, view.rates, now)) return false
        val admitAt = Serving.admitAt(view.room.opensAt, view.rates, number) ?: return false
        return now >= admitAt.plus(view.room.entryTtl)
    }

    /** 오픈 시각 뒤에는 섞기가 끝난 방만 쓴다 - 첫 요청들이 몰려도 [WaitingRoomStore.shuffle] 이 한 번만 하고, 진 쪽은 이긴 쪽이 끝난 뒤 돌아온다 */
    private fun shuffled(view: RoomView, now: Instant): RoomView {
        if (view.room.shuffledAt != null) return view
        store.shuffle(view.room.id, now)
        cache.remove(view.room.id)
        return view(view.room.id)
    }

    private fun currentRate(view: RoomView, now: Instant): Int =
        view.rates.sortedBy { it.fromAt }.lastOrNull { it.fromAt <= maxOf(now, view.room.opensAt) }?.perMinute ?: 0

    private fun secondsUntil(at: Instant, now: Instant): Long = maxOf(0L, Math.ceilDiv(Duration.between(now, at).toMillis(), 1000L))

    /** 묻는 간격 - 앞 사람이 많을수록 길게 5 ~ 30초, 오픈 전에는 오픈이 가까울수록 짧게. 한꺼번에 몰리지 않게 20% 안에서 흔든다 */
    private fun pollAfter(ahead: Long?, untilOpen: Duration?): Int {
        val base = when {
            untilOpen != null -> (untilOpen.seconds / 6).coerceIn(MIN_POLL.toLong(), MAX_POLL.toLong())
            else -> (MIN_POLL + (ahead ?: 0) / 200).coerceIn(MIN_POLL.toLong(), MAX_POLL.toLong())
        }.toInt()
        return base + ThreadLocalRandom.current().nextInt(base / 5 + 1)
    }

    private fun view(id: String, fresh: Boolean = false): RoomView {
        val now = time.now()
        cache[id]?.let { if (!fresh && now >= it.loadedAt && Duration.between(it.loadedAt, now) < ROOM_CACHE) return it }
        val room = store.findRoom(id) ?: throw RoomNotFoundException()
        return RoomView(room, store.rates(id), now).also { cache[id] = it }
    }

    private companion object {
        const val MIN_POLL = 5
        const val MAX_POLL = 30
    }
}
