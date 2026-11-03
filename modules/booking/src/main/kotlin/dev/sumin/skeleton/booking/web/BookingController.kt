package dev.sumin.skeleton.booking.web

import dev.sumin.skeleton.booking.BookingAdmission
import dev.sumin.skeleton.booking.BookingHolds
import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.Response
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 예약 사용자 경로 — 풀 · 배치도 스냅샷 · 배치도 흐름 · 자리 잡기 / 놓기 · 내 보류 · 확정 / 놓기 / 취소.
 * 남의 · 없는 보류는 같은 404. 스냅샷과 흐름은 로그인한 사람만(캐시 없는 DB 읽기 · 열린 연결이라 익명에 열면 누구나 서버 자원을 붙잡는다).
 * 대기실을 단 풀은 자리를 더하거나 흐름을 열 때 `X-Entry-Token`(입장권)을 [BookingAdmission] 이 검사한다. 결제는 기존 `POST /api/v1/payments/orders {itemType: "booking", itemRef: holdId}` 를 쓴다.
 */
@RestController
@RequestMapping("/api/v1/booking")
@Tag(name = "Booking")
class BookingController(
    private val pools: BookingPools,
    private val holds: BookingHolds,
    private val streams: BookingSeatStreamService,
    private val admission: BookingAdmission,
) {
    @Operation(summary = "A pool and how many seats are available (public)")
    @GetMapping("/pools/{id}")
    fun pool(@PathVariable id: String): DataResponse<PoolResponse> {
        val pool = pools.get(id)
        return Response.ok(PoolResponse.of(pool, pools.counts(pool.id)))
    }

    @Operation(
        summary = "Seat map snapshot with the state of every seat (not cached)",
        description = "state is OPEN or TAKEN; a seat held by someone carries heldUntil. Each seat has a version v - keep the larger v when merging stream events.",
    )
    @GetMapping("/pools/{id}/seats")
    fun snapshot(@PathVariable id: String, authentication: Authentication?): ResponseEntity<DataResponse<SnapshotResponse>> {
        BookingCallers.account(authentication)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.ok(SnapshotResponse.of(pools.snapshot(id))))
    }

    @Operation(
        summary = "Seat map stream (text/event-stream): `ready`, then `seats` events",
        description = "Open the stream first, read the snapshot after `ready`, keep the larger v per seat. Pools with a waiting room need X-Entry-Token. The server closes the stream after 30 minutes - reconnect and read the snapshot again.",
    )
    @GetMapping("/pools/{id}/seats/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable id: String,
        @RequestHeader(name = ENTRY_TOKEN_HEADER, required = false) entryToken: String?,
        authentication: Authentication?,
    ): SseEmitter {
        val account = BookingCallers.account(authentication)
        admission.check(pools.get(id), account, entryToken)
        return streams.connect(id)
    }

    @Operation(
        summary = "Take seats into my hold (all or nothing; picking a seat that is already in my hold does nothing)",
        description = "409 BOOKING.SEAT_TAKEN (data.seatIds) · LIMIT_REACHED · NOT_OPEN · HOLD_EXPIRED · HOLD_LOCKED · 403 WAITING_ROOM.ENTRY_REQUIRED.",
    )
    @PostMapping("/pools/{id}/hold/seats")
    fun hold(
        @PathVariable id: String,
        @RequestBody body: HoldSeatsRequest,
        @RequestHeader(name = ENTRY_TOKEN_HEADER, required = false) entryToken: String?,
        authentication: Authentication?,
    ): DataResponse<HoldResponse> =
        Response.ok(HoldResponse.of(holds.addSeats(id, BookingCallers.account(authentication), body.seatIds.orEmpty(), entryToken)))

    @Operation(summary = "Let go of one seat of my hold (pressing a held seat again)")
    @DeleteMapping("/pools/{id}/hold/seats/{seatId}")
    fun unhold(@PathVariable id: String, @PathVariable seatId: String, authentication: Authentication?): DataResponse<HoldResponse> =
        Response.ok(HoldResponse.of(holds.removeSeat(id, BookingCallers.account(authentication), seatId)))

    @Operation(summary = "My open holds and confirmed bookings")
    @GetMapping("/holds/me")
    fun mine(authentication: Authentication?): ListResponse<HoldResponse> =
        Response.ok(holds.mine(BookingCallers.account(authentication)).map(HoldResponse::of))

    @Operation(summary = "Confirm a free hold (priced seats are confirmed by payment)")
    @PostMapping("/holds/{id}/confirm")
    fun confirm(@PathVariable id: String, authentication: Authentication?): DataResponse<HoldResponse> =
        Response.ok(HoldResponse.of(holds.confirm(BookingCallers.account(authentication), id)))

    @Operation(
        summary = "Start the checkout of my hold: pins the amount (sum of the seat prices) and locks the seats",
        description = "Idempotent - calling again returns the same hold and amount. After this, adding, removing or releasing seats is 409 BOOKING.HOLD_LOCKED; the hold still ends at expiresAt. " +
            "Then create the payment order with POST /api/v1/payments/orders {itemType: \"booking\", itemRef: holdId}; an order for a hold that has not started is 409 BOOKING.CHECKOUT_REQUIRED. 409 NOT_PAYABLE for free or confirmed holds.",
    )
    @PostMapping("/holds/{id}/checkout")
    fun checkout(@PathVariable id: String, authentication: Authentication?): DataResponse<HoldResponse> =
        Response.ok(HoldResponse.of(holds.startCheckout(BookingCallers.account(authentication), id)))

    @Operation(summary = "Let go of every seat of my hold (does nothing to a hold that is already confirmed; 409 BOOKING.HOLD_LOCKED once the checkout has started)")
    @PostMapping("/holds/{id}/release")
    fun release(@PathVariable id: String, authentication: Authentication?): DataResponse<HoldResponse> =
        Response.ok(HoldResponse.of(holds.release(BookingCallers.account(authentication), id)))

    @Operation(summary = "Cancel a confirmed free booking (until the pool's sales end)")
    @PostMapping("/holds/{id}/cancel")
    fun cancel(@PathVariable id: String, authentication: Authentication?): DataResponse<HoldResponse> =
        Response.ok(HoldResponse.of(holds.cancel(BookingCallers.account(authentication), id)))

    companion object {
        const val ENTRY_TOKEN_HEADER = "X-Entry-Token"
    }
}
