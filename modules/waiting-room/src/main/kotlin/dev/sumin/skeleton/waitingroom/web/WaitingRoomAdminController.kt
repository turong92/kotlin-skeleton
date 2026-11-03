package dev.sumin.skeleton.waitingroom.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.waitingroom.NewWaitingRoom
import dev.sumin.skeleton.waitingroom.WaitingRoomPermissions
import dev.sumin.skeleton.waitingroom.WaitingRooms
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대기실 운영자 경로(`skeleton.waiting-room.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 핸들러마다 `waiting-room.manage` 를 [OperatorAccess] 로 판정한다(익명 401 · 권한 없음 403).
 */
@RestController
@RequestMapping("/api/v1/admin/waiting-rooms")
@Tag(name = "Waiting room admin")
class WaitingRoomAdminController(
    private val rooms: WaitingRooms,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
) {
    @Operation(summary = "Create a waiting room (perMinute is required - there is no default)")
    @PostMapping
    fun create(authentication: Authentication?, @RequestBody body: CreateWaitingRoomRequest): DataResponse<WaitingRoomResponse> {
        val operator = operator(authentication)
        val scopeKey = body.scopeKey ?: throw FieldValidationException("scopeKey", "Required", "scopeKey is required")
        val opensAt = body.opensAt ?: throw FieldValidationException("opensAt", "Required", "opensAt is required")
        val perMinute = body.perMinute ?: throw FieldValidationException("perMinute", "Required", "perMinute is required")
        val room = rooms.create(
            NewWaitingRoom(
                scopeKey, opensAt, perMinute,
                body.prequeueMinutes ?: NewWaitingRoom.DEFAULT_PREQUEUE_MINUTES,
                body.entryMinutes ?: NewWaitingRoom.DEFAULT_ENTRY_MINUTES,
            ),
        )
        audit.record(OperatorAction(operator.accountId, "waiting-room.created", "waiting-room", room.id, mapOf("perMinute" to perMinute.toString())))
        return Response.ok(WaitingRoomResponse.of(room))
    }

    @Operation(summary = "Change how many people are let in per minute from now on (0 pauses)")
    @PostMapping("/{id}/rate")
    fun rate(authentication: Authentication?, @PathVariable id: String, @RequestBody body: SetRateRequest): DataResponse<WaitingRoomStatsResponse> {
        val operator = operator(authentication)
        val perMinute = body.perMinute ?: throw FieldValidationException("perMinute", "Required", "perMinute is required")
        rooms.setRate(id, perMinute)
        audit.record(OperatorAction(operator.accountId, "waiting-room.rate.changed", "waiting-room", id, mapOf("perMinute" to perMinute.toString())))
        return Response.ok(WaitingRoomStatsResponse.of(rooms.stats(id)))
    }

    @Operation(summary = "Close the room - no new tickets, asking answers CLOSED (entry tokens already issued last until they expire)")
    @PostMapping("/{id}/close")
    fun close(authentication: Authentication?, @PathVariable id: String): DataResponse<WaitingRoomResponse> {
        val operator = operator(authentication)
        rooms.close(id)
        audit.record(OperatorAction(operator.accountId, "waiting-room.closed", "waiting-room", id, emptyMap()))
        return Response.ok(WaitingRoomResponse.of(rooms.get(id)))
    }

    @Operation(summary = "Counts: waiting before the opening, numbers issued, the number being served, rate and time to drain")
    @GetMapping("/{id}/stats")
    fun stats(authentication: Authentication?, @PathVariable id: String): DataResponse<WaitingRoomStatsResponse> {
        operator(authentication)
        return Response.ok(WaitingRoomStatsResponse.of(rooms.stats(id)))
    }

    private fun operator(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, WaitingRoomPermissions.MANAGE) }
    }
}
