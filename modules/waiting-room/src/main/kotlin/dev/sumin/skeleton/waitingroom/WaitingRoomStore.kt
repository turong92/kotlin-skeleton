package dev.sumin.skeleton.waitingroom

import java.time.Instant

/**
 * 대기열 저장 포트. 구현은 `waiting-room-jdbc`. 모든 번호는 이 포트가 정한다 - 번호는 1..n 빈틈 없는 순열이어야 한다.
 */
interface WaitingRoomStore {
    /** [scopeKey] 가 이미 있으면 [RoomExistsException] */
    fun createRoom(room: NewWaitingRoom, now: Instant): WaitingRoom

    fun findRoom(id: String): WaitingRoom?

    fun findRoomByScope(scopeKey: String): WaitingRoom?

    /** 시작 시각 순. 늘 하나 이상(오픈 시각의 첫 구간) */
    fun rates(roomId: String): List<RateSegment>

    /** [now] 부터 새 속도. 같은 시각의 구간이 있으면 바꾼다 */
    fun setRate(roomId: String, perMinute: Int, now: Instant)

    fun close(roomId: String)

    /**
     * 오픈 전 줄을 무작위로 섞어 1..n 번호를 매긴다. **방마다 한 번만** - 이긴 호출만 true 를 돌려주고(번호가 다 매겨진 뒤 커밋), 진 호출은 이긴 쪽이 끝난 뒤 false 로 돌아온다.
     */
    fun shuffle(roomId: String, now: Instant): Boolean

    fun findTicket(roomId: String, accountId: String): WaitingTicket?

    /** 오픈 전 줄에 번호 없이 선다. 같은 계정이 이미 있으면 그 순번표 */
    fun addPrequeueTicket(roomId: String, accountId: String, now: Instant): WaitingTicket

    /** 오픈 뒤(섞기 뒤) 맨 뒤 번호로 선다. 같은 계정이 이미 있으면 그 순번표 */
    fun addTailTicket(roomId: String, accountId: String, now: Instant): WaitingTicket

    /**
     * 순번표에 맨 뒤 번호를 새로 준다 - [expected] 가 그 순번표의 지금 번호일 때만(번호 없는 늦은 줄 null, 입장 시간이 지난 번호 n).
     * 그 사이 다른 호출이 먼저 바꿨으면 아무것도 하지 않고 지금 순번표를 돌려준다.
     */
    fun renumber(ticket: WaitingTicket, expected: Long?, now: Instant): WaitingTicket

    /** 번호 없이 서 있는 사람 수 */
    fun countPrequeue(roomId: String): Long
}
