package dev.sumin.skeleton.booking.web

import dev.sumin.skeleton.booking.BookingPermissions
import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.booking.NewBookingPool
import dev.sumin.skeleton.booking.NewSeat
import dev.sumin.skeleton.booking.PoolStatus
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 예약 운영자 경로(`skeleton.booking.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 핸들러마다 권한을 [OperatorAccess] 로 판정한다(익명 401 · 권한 없음 403):
 * 읽기(목록 · 현황)는 `booking.read`, 쓰기(만들기 · 고치기 · 자리 넣기 · 막기/풀기)는 `booking.manage`.
 */
@RestController
@RequestMapping("/api/v1/admin/booking/pools")
@Tag(name = "Booking admin")
class BookingAdminController(
    private val pools: BookingPools,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
) {
    @Operation(summary = "Pools, newest first (up to 200)")
    @GetMapping
    fun list(authentication: Authentication?): ListResponse<AdminPoolResponse> {
        operator(authentication, BookingPermissions.READ)
        return Response.ok(pools.list().map(AdminPoolResponse::of))
    }

    @Operation(summary = "Create a pool (name, opensAt and maxPerHold are required - there is no default for how many seats one person may take)")
    @PostMapping
    fun create(authentication: Authentication?, @RequestBody body: CreatePoolRequest): DataResponse<AdminPoolResponse> {
        val operator = operator(authentication, BookingPermissions.MANAGE)
        val name = body.name ?: throw FieldValidationException("name", "Required", "name is required")
        val opensAt = body.opensAt ?: throw FieldValidationException("opensAt", "Required", "opensAt is required")
        val maxPerHold = body.maxPerHold ?: throw FieldValidationException("maxPerHold", "Required", "maxPerHold is required")
        val pool = valid {
            pools.create(
                NewBookingPool(
                    name, opensAt, body.closesAt, maxPerHold, body.perAccountLimit, body.currency,
                    body.holdMinutes ?: dev.sumin.skeleton.booking.DEFAULT_HOLD_MINUTES, body.waitingRoomId,
                ),
            )
        }
        audit.record(OperatorAction(operator.accountId, "booking.pool.created", "booking-pool", pool.id, emptyMap()))
        return Response.ok(AdminPoolResponse.of(pool))
    }

    @Operation(summary = "Rename, change the sales end, or set the status (ACTIVE | PAUSED | CLOSED) - only the fields sent change")
    @PatchMapping("/{id}")
    fun update(authentication: Authentication?, @PathVariable id: String, @RequestBody body: UpdatePoolRequest): DataResponse<AdminPoolResponse> {
        val operator = operator(authentication, BookingPermissions.MANAGE)
        val status = body.status?.let { raw -> PoolStatus.entries.firstOrNull { it.name == raw } ?: throw FieldValidationException("status", "Invalid", "status must be one of ${PoolStatus.entries}") }
        val pool = pools.update(id, body.name, body.closesAt, status)
        audit.record(OperatorAction(operator.accountId, "booking.pool.updated", "booking-pool", id, mapOf("status" to pool.status.name)))
        return Response.ok(AdminPoolResponse.of(pool))
    }

    @Operation(
        summary = "Add seats (section, row, number, grade, optional price)",
        description = "409 BOOKING.SEAT_EXISTS when the same section/row/number is already in the pool - nothing is added. A priced seat needs the pool to have a currency.",
    )
    @PostMapping("/{id}/seats")
    fun addSeats(authentication: Authentication?, @PathVariable id: String, @RequestBody body: AddSeatsRequest): DataResponse<AddedSeatsResponse> {
        val operator = operator(authentication, BookingPermissions.MANAGE)
        val seats = valid {
            body.seats.orEmpty().map {
                NewSeat(
                    it.section ?: throw FieldValidationException("seats", "Required", "section is required"),
                    it.row ?: throw FieldValidationException("seats", "Required", "row is required"),
                    it.number ?: throw FieldValidationException("seats", "Required", "number is required"),
                    it.grade ?: throw FieldValidationException("seats", "Required", "grade is required"),
                    it.price,
                )
            }
        }
        if (seats.isEmpty()) throw FieldValidationException("seats", "Size", "add at least one seat")
        val added = pools.addSeats(id, seats).size
        audit.record(OperatorAction(operator.accountId, "booking.seats.added", "booking-pool", id, mapOf("count" to added.toString())))
        return Response.ok(AddedSeatsResponse(added))
    }

    @Operation(summary = "Block seats (AVAILABLE to BLOCKED)", description = "409 BOOKING.SEAT_IN_USE (data.seatIds) when a seat is held or sold - a sold seat cannot be taken back this way.")
    @PostMapping("/{id}/seats/block")
    fun block(authentication: Authentication?, @PathVariable id: String, @RequestBody body: SeatIdsRequest): DataResponse<ChangedSeatsResponse> {
        val operator = operator(authentication, BookingPermissions.MANAGE)
        val changed = pools.block(id, seatIds(body))
        audit.record(OperatorAction(operator.accountId, "booking.seats.blocked", "booking-pool", id, mapOf("count" to changed.toString())))
        return Response.ok(ChangedSeatsResponse(changed))
    }

    @Operation(summary = "Unblock seats (BLOCKED to AVAILABLE); seats that are not blocked are left alone")
    @PostMapping("/{id}/seats/unblock")
    fun unblock(authentication: Authentication?, @PathVariable id: String, @RequestBody body: SeatIdsRequest): DataResponse<ChangedSeatsResponse> {
        val operator = operator(authentication, BookingPermissions.MANAGE)
        val changed = pools.unblock(id, seatIds(body))
        audit.record(OperatorAction(operator.accountId, "booking.seats.unblocked", "booking-pool", id, mapOf("count" to changed.toString())))
        return Response.ok(ChangedSeatsResponse(changed))
    }

    @Operation(summary = "Counts of seats by state; unfulfilled = paid holds that could not be given their seats (a refund job is queued for each)")
    @GetMapping("/{id}/stats")
    fun stats(authentication: Authentication?, @PathVariable id: String): DataResponse<PoolStatsResponse> {
        operator(authentication, BookingPermissions.READ)
        return Response.ok(PoolStatsResponse.of(pools.stats(id)))
    }

    private fun seatIds(body: SeatIdsRequest): List<String> =
        body.seatIds.orEmpty().also { if (it.isEmpty()) throw FieldValidationException("seatIds", "Size", "pick at least one seat") }

    /** 모델의 `require` 가 던지는 입력 오류를 400 으로 옮긴다 */
    private fun <T> valid(block: () -> T): T =
        try {
            block()
        } catch (ex: IllegalArgumentException) {
            throw FieldValidationException("body", "Invalid", ex.message ?: "invalid request")
        }

    private fun operator(authentication: Authentication?, permission: String): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, permission) }
    }
}
