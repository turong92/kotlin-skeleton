package dev.sumin.skeleton.booking

import dev.sumin.skeleton.waitingroom.WaitingRooms

/**
 * 대기실을 단 풀(`waitingRoomId`)은 자리를 더할 때마다 · 배치도 흐름을 열 때 **입장권**을 본다 - 없거나 위조 · 만료 · 남의 것이면 `403 WAITING_ROOM.ENTRY_REQUIRED`.
 * 대기실이 없는 풀은 막지 않는다. 입장권은 서명만 보고 DB 를 읽지 않아 자리를 더할 때마다 불러도 싸다.
 * 입장 시간이 지난 계정은 새 자리를 못 잡지만, 이미 잡은 보류는 결제 · 확정할 수 있다(그 길은 문을 거치지 않는다).
 */
class WaitingRoomBookingAdmission(private val rooms: WaitingRooms) : BookingAdmission {
    override fun check(pool: BookingPool, accountId: String, entryToken: String?) {
        val roomId = pool.waitingRoomId ?: return
        rooms.requireEntry(roomId, accountId, entryToken)
    }
}
