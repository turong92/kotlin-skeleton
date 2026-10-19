package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.web.AmountResponse
import dev.sumin.skeleton.payment.web.CardResponse
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import java.util.Base64
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** `GET /cheers/options` 의 한 줄 — 서버가 가진 금액 목록. 브라우저는 이 안에서만 고른다. */
data class CheerOptionResponse(val itemRef: String, val amount: AmountResponse)

/** `GET /cheers/me` 의 한 줄. `card` 는 장부의 주문에서 읽는다 — 카드 결제가 아니거나 계정이 지워져 비워졌으면 `null`. 결제사 결제 번호 · 계정 id 는 싣지 않는다. */
data class CheerResponse(
    val orderId: String,
    val amount: AmountResponse,
    val refundedAmount: Long,
    val status: String,
    val createdAt: Instant,
    val refundedAt: Instant?,
    val card: CardResponse?,
    /** 결제사가 직접 받은 간편결제의 브랜드 코드(`KAKAOPAY` · `NAVERPAY`) - 없거나 모르면 `null`. 장부 주문의 `easyPayBrand` 그대로. */
    val easyPayBrand: String?,
)

/**
 * 응원하기 — HTTP 변환만. 금액 목록과 내 응원 내역만 안다: 결제 자체(주문 → 결제창 → 승인 → 환불 요청)는 결제 장부의 `/api/v1/payments` 가 맡는다.
 * 호출자는 `Authentication.name`(= 계정 id)이고 인증 없는 호출은 보안 체인이 401 로 막는다.
 */
@RestController
@RequestMapping("/api/v1/cheers")
@Tag(name = "Cheers")
class CheerController(private val cheers: CheerRepository, private val ledger: PaymentLedgerStore) {
    @GetMapping("/options")
    fun options(): ListResponse<CheerOptionResponse> =
        Response.ok(CheerCatalog.options.map { CheerOptionResponse(it.itemRef, AmountResponse(it.amount.amount, it.amount.normalizedCurrency)) })

    @GetMapping("/me")
    fun mine(authentication: Authentication, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) size: Int?): CursorResponse<CheerResponse> {
        val limit = (size ?: DEFAULT_SIZE).coerceIn(1, MAX_SIZE)
        val rows = cheers.listByAccount(authentication.name, decode(cursor), limit + 1)
        val page = rows.take(limit)
        // 카드는 장부의 주문 줄에서 한 번에 읽는다(줄마다 묻지 않는다). 주문 줄이 없으면(시험 데이터 · 옛 데이터) 카드만 비어 보인다
        val orders = if (page.isEmpty()) emptyMap() else ledger.listOrdersByIds(page.map { it.orderId }).associateBy { it.id }
        val values = page.map { cheer ->
            val order = orders[cheer.orderId]
            CheerResponse(
                orderId = cheer.orderId,
                amount = AmountResponse(cheer.amount, cheer.currency),
                refundedAmount = cheer.refundedAmount,
                status = cheer.status.name,
                createdAt = cheer.createdAt,
                refundedAt = cheer.refundedAt,
                card = order?.takeIf { it.cardIssuer != null || it.cardLast4 != null }?.let { CardResponse(it.cardIssuer, it.cardLast4) },
                easyPayBrand = order?.easyPayBrand,
            )
        }
        val hasNext = rows.size > limit
        return Response.cursor(values, if (hasNext) encode(page.last()) else null, hasNext)
    }

    /** 커서는 불투명한 글자 하나다(`<시각 마이크로초>.<주문 id>` 의 URL 안전 base64) — 읽을 수 없으면 400. */
    private fun encode(last: Cheer): String {
        val micros = last.createdAt.epochSecond * 1_000_000 + last.createdAt.nano / 1_000
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$micros.${last.orderId}".toByteArray(Charsets.UTF_8))
    }

    private fun decode(text: String?): CheerCursor? {
        if (text.isNullOrBlank()) return null
        val raw = try {
            String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw invalidCursor()
        }
        val micros = raw.substringBefore('.', "").toLongOrNull()?.takeIf { it in 0..MAX_CURSOR_MICROS } ?: throw invalidCursor()   // 손댄 커서가 저장소의 시각 범위 밖으로 질의를 보내 500 을 만들지 못하게
        val orderId = raw.substringAfter('.', "").takeIf { it.isNotEmpty() && it.length <= 64 } ?: throw invalidCursor()
        return CheerCursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000), orderId)
    }

    private fun invalidCursor() = FieldValidationException("cursor", "Pattern", "cursor is not one this list gave out")

    private companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 50

        /** 커서가 가질 수 있는 가장 큰 시각(9999-12-31T23:59:59.999999Z, 마이크로초) — 진짜 줄의 시각은 1970 년 이후 이 안이다. */
        const val MAX_CURSOR_MICROS = 253_402_300_799_999_999L
    }
}
