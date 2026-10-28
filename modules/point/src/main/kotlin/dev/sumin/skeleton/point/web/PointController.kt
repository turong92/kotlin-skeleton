package dev.sumin.skeleton.point.web

import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.point.PointCursor
import dev.sumin.skeleton.point.PointWallet
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 내 포인트(읽기만 - 포인트를 쓰는 길은 서버 API `PointWallet.spend` 뿐이다). `skeleton.point.http.enabled=false` 로 닫는다 */
@RestController
@RequestMapping("/api/v1/points")
@Tag(name = "Point")
class PointController(private val wallet: PointWallet) {
    @Operation(summary = "My balance and the points expiring within 30 days")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PointBalanceResponse::class)
    @GetMapping
    fun balance(authentication: Authentication?): DataResponse<PointBalanceResponse> =
        Response.ok(PointBalanceResponse.of(wallet.balance(PointCallers.account(authentication))))

    @Operation(summary = "My point history, newest first")
    @ApiResponseEnvelope(type = ApiEnvelopeType.CURSOR, value = PointEntryResponse::class)
    @GetMapping("/history")
    fun history(
        authentication: Authentication?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<PointEntryResponse> = page(wallet, PointCallers.account(authentication), cursor, size)
}

internal fun page(wallet: PointWallet, accountId: String, cursor: String?, size: Int?): CursorResponse<PointEntryResponse> {
    val limit = (size ?: DEFAULT_SIZE).coerceIn(1, MAX_SIZE)
    val rows = wallet.history(accountId, PointCursors.decode(cursor), limit + 1)
    val shown = rows.take(limit)
    val next = if (rows.size > limit) shown.last().let { PointCursors.encode(PointCursor(it.createdAt, it.id)) } else null
    return Response.cursor(shown.map(PointEntryResponse::of), next)
}

internal const val DEFAULT_SIZE = 20
internal const val MAX_SIZE = 100
