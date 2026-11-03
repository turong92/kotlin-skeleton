package dev.sumin.skeleton.waitingroom

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import java.time.Instant
import org.springframework.http.HttpStatus

enum class WaitingRoomErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    ENTRY_REQUIRED("WAITING_ROOM.ENTRY_REQUIRED", HttpStatus.FORBIDDEN, "대기실의 입장권이 필요하다"),
    TICKET_INVALID("WAITING_ROOM.TICKET_INVALID", HttpStatus.FORBIDDEN, "순번표가 올바르지 않다"),
    NOT_OPEN("WAITING_ROOM.NOT_OPEN", HttpStatus.CONFLICT, "아직 줄을 설 수 있는 때가 아니다"),
    CLOSED("WAITING_ROOM.CLOSED", HttpStatus.CONFLICT, "닫힌 대기실이다"),
    ROOM_EXISTS("WAITING_ROOM.ROOM_EXISTS", HttpStatus.CONFLICT, "같은 범위 키의 대기실이 이미 있다"),
    ROOM_NOT_FOUND("WAITING_ROOM.ROOM_NOT_FOUND", HttpStatus.NOT_FOUND, "대기실이 없다"),
}

/** 입장권이 없거나 올바르지 않거나 지났다 - 어느 쪽인지는 알리지 않는다 */
class EntryRequiredException : ApplicationException("waiting room entry required", WaitingRoomErrorCode.ENTRY_REQUIRED)

/** 순번표가 위조 · 변조 · 다른 방 · 다른 계정의 것이다 */
class TicketInvalidException : ApplicationException("waiting room ticket invalid", WaitingRoomErrorCode.TICKET_INVALID)

class WaitingRoomNotOpenException(opensAt: Instant, prequeueStartsAt: Instant) :
    ApplicationException(
        "waiting room is not taking tickets yet",
        WaitingRoomErrorCode.NOT_OPEN,
        data = mapOf("opensAt" to opensAt, "prequeueStartsAt" to prequeueStartsAt),
    )

class WaitingRoomClosedException : ApplicationException("waiting room is closed", WaitingRoomErrorCode.CLOSED)

class RoomExistsException : ApplicationException("waiting room scope exists", WaitingRoomErrorCode.ROOM_EXISTS)

class RoomNotFoundException : ApplicationException("waiting room not found", WaitingRoomErrorCode.ROOM_NOT_FOUND)
