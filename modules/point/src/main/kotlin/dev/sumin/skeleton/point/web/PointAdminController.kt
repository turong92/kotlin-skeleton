package dev.sumin.skeleton.point.web

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.PointPermissions
import dev.sumin.skeleton.point.PointWallet
import dev.sumin.skeleton.point.StorePointWallet
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.temporal.ChronoUnit
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 포인트 운영 (`point.manage`). `skeleton.point.admin.enabled=true` 일 때만 등록된다 (꺼져 있으면 404) */
@RestController
@RequestMapping("/api/v1/admin/points")
@Tag(name = "Point")
class PointAdminController(
    private val wallet: PointWallet,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
    private val time: TimeProvider,
    private val tx: TransactionTemplate?,
) {
    @Operation(summary = "One account's balance and newest history (point.manage)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminPointAccountResponse::class)
    @GetMapping("/{accountId}")
    fun account(authentication: Authentication?, @PathVariable accountId: String): DataResponse<AdminPointAccountResponse> {
        access.require(PointCallers.operatorOf(authentication), PointPermissions.MANAGE)
        val entries = wallet.history(accountId, null, ENTRIES)
        return Response.ok(AdminPointAccountResponse(wallet.balance(accountId).balance, entries.map(PointEntryResponse::of)))
    }

    @Operation(
        summary = "Add or take points with a reason (point.manage)",
        description = "delta above 0 earns (expiresInDays optional), below 0 deducts. 409 POINT.INSUFFICIENT when the balance is smaller; nothing changes.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PointEntryResponse::class)
    @PostMapping("/{accountId}/adjustments")
    fun adjust(
        authentication: Authentication?,
        @PathVariable accountId: String,
        @RequestBody body: PointAdjustmentRequest,
    ): ResponseEntity<DataResponse<PointEntryResponse>> {
        val operator = PointCallers.operatorOf(authentication)
        access.require(operator, PointPermissions.MANAGE)
        val delta = body.delta ?: throw FieldValidationException("delta", "NotNull", "delta is required")
        val reason = body.reason?.trim().orEmpty()
        if (delta == 0L) throw FieldValidationException("delta", "Range", "delta must not be zero")
        if (kotlin.math.abs(delta) > StorePointWallet.MAX_AMOUNT) throw FieldValidationException("delta", "Range", "delta is too large")
        if (reason.isEmpty() || reason.length > StorePointWallet.MAX_REASON) throw FieldValidationException("reason", "Size", "reason is required, up to 200 characters")
        val days = body.expiresInDays
        if (days != null && (delta < 0 || days !in 1..36_500)) throw FieldValidationException("expiresInDays", "Range", "expiresInDays applies to an addition, 1..36500")
        val expiresAt = days?.let { time.now().plus(it.toLong(), ChronoUnit.DAYS) }

        // 조정과 운영 기록은 한 트랜잭션 - 기록이 실패하면 조정도 없던 일이 된다
        val entry = inTransaction {
            wallet.adjust(accountId, delta, reason, expiresAt, operator!!.accountId).also {
                audit.record(OperatorAction(operator.accountId, "point.adjusted", "point_entry", it.id))
            }
        }
        return ResponseEntity.status(201).body(Response.ok(PointEntryResponse.of(entry)))
    }

    private fun <T> inTransaction(block: () -> T): T = tx?.execute { block() } ?: block()

    private companion object {
        const val ENTRIES = 50
    }
}
