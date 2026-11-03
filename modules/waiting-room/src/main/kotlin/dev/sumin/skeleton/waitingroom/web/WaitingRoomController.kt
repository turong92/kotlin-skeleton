package dev.sumin.skeleton.waitingroom.web

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.waitingroom.WaitingRooms
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대기실 사용자 경로 - 로그인한 사람만(1인 1순번 = 계정 1개 1순번). 순번표는 계정에 묶여 남의 순번표로는 묻지 못한다(`403 WAITING_ROOM.TICKET_INVALID`).
 * 입장권은 묻기 답의 `entryToken` 을 예약 같은 쪽 요청의 `X-Entry-Token` 헤더에 싣는다.
 */
@RestController
@RequestMapping("/api/v1/waiting-rooms")
@Tag(name = "Waiting room")
class WaitingRoomController(private val rooms: WaitingRooms) {
    @Operation(
        summary = "Take a place in line (the same account always gets the same ticket)",
        description = "Before the opening there is no number - the line is shuffled once at the opening. 409 WAITING_ROOM.NOT_OPEN (data.opensAt, data.prequeueStartsAt) before the pre-queue starts · WAITING_ROOM.CLOSED. " +
            "The answer carries ticket - keep it and send it as X-Ticket when asking.",
    )
    @PostMapping("/{id}/tickets")
    fun ticket(@PathVariable id: String, authentication: Authentication?): ResponseEntity<DataResponse<QueueStatusResponse>> =
        noStore(rooms.issueTicket(id, WaitingRoomCallers.account(authentication)))

    @Operation(
        summary = "Where am I in line (reads no database for most calls)",
        description = "state is PREQUEUE | WAITING | ADMITTED | EXPIRED | CLOSED. ADMITTED carries entryToken (valid until the entry time ends). EXPIRED - take a ticket again (the back of the line). " +
            "Ask again after pollAfterSeconds. If the answer carries ticket, replace the stored one. 403 WAITING_ROOM.TICKET_INVALID for a missing, forged or another account's ticket.",
    )
    @GetMapping("/{id}/status")
    fun status(
        @PathVariable id: String,
        @RequestHeader(name = TICKET_HEADER, required = false) ticket: String?,
        authentication: Authentication?,
    ): ResponseEntity<DataResponse<QueueStatusResponse>> = noStore(rooms.status(id, WaitingRoomCallers.account(authentication), ticket))

    private fun noStore(status: dev.sumin.skeleton.waitingroom.QueueStatus): ResponseEntity<DataResponse<QueueStatusResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.ok(QueueStatusResponse.of(status)))

    companion object {
        const val TICKET_HEADER = "X-Ticket"
    }
}
