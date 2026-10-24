package dev.sumin.skeleton.payment

import java.time.Duration
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

/** 구독 상태 — 전이는 [SubscriptionTransitions] 한 곳이 정한다(스펙 `2026-10-09-subscription-design.md` §3). */
enum class SubscriptionStatus {
    /** 만들었고 카드 등록 · 첫 결제 전(또는 확인 중). */
    PENDING,

    /** 이용 중 — 기간 끝에 갱신. */
    ACTIVE,

    /** 갱신 실패 — 재시도 중, 이용 불가. */
    PAST_DUE,

    /** 해지 예약 — 기간 끝까지 이용, 갱신 없음. */
    CANCELING,

    /** 끝. 다시 구독하면 새 줄. */
    ENDED,
    ;

    /** 앱이 기능을 열어 줘도 되는 상태. */
    val isInUse: Boolean get() = this == ACTIVE || this == CANCELING
}

/** 구독이 끝난 이유(`end_reason`) — 화면이 문구를 고른다. */
enum class SubscriptionEndReason {
    /** 첫 결제를 결제사가 거절했다. */
    FIRST_PAYMENT_DECLINED,

    /** 본인이 해지했다(기간 끝에 끝났거나, 갱신 실패 중에 해지했다). */
    USER_CANCELED,

    /** 본인이 중도 해지했다(지금 끝내고 이번 기간의 쓰지 않은 몫을 환불). */
    USER_CANCELED_NOW,

    /** 갱신 재시도를 다 썼다. */
    PAYMENT_FAILED,

    /** 계정이 탈퇴했다. */
    ACCOUNT_ERASED,

    /** 앱이 이 계정의 갱신 과금을 막았다([SubscriptionRenewalGuard] — 정지 · 삭제 유예). */
    RENEWAL_BLOCKED,

    /** 그 기간의 결제가 전액 환불됐다. */
    REFUNDED,
}

/**
 * 구독 한 줄(스펙 §4). 개인정보로 볼 수 있는 칸([customerKey] · [cardIssuer] · [cardLast4])과 비밀([billingToken], **암호문**)이 있어 [toString] 은 찍지 않는다.
 * [billingToken] 은 `crypto` 모듈로 암호화한 값이다 — 평문은 결제사를 부르는 순간에만 메모리에 있다.
 */
data class PaymentSubscription(
    val id: String,
    val accountId: String,
    val provider: String,
    val providerMode: PaymentMode,
    val status: SubscriptionStatus,
    val planCode: String,
    /** 박힌 금액 — 카탈로그 가격이 바뀌어도 기존 구독에 조용히 적용되지 않는다. */
    val amount: PaymentAmount,
    /** ISO-8601 (`P1M` · `PT3M`) — [SubscriptionPeriod]. */
    val period: String,
    /** 첫 결제 시각 — 기간 끝은 늘 여기서 센다(누적 밀림 없음). 첫 결제 전에는 null. */
    val anchorAt: Instant?,
    val periodSeq: Int,
    val currentPeriodStart: Instant?,
    val currentPeriodEnd: Instant?,
    /** 지금 과금 중인(또는 마지막으로 과금한) 주문. */
    val chargeOrderId: String?,
    /** 이번 기간의 시도 번호(1부터). */
    val attempt: Int,
    val customerKey: String?,
    val billingToken: String?,
    val cardIssuer: String?,
    val cardLast4: String?,
    /** 살아 있는 동안 = 계정 id, 끝나면 null → 계정당 살아 있는 구독 하나(유니크). 카드 등록 · 첫 결제를 시작할 때 박힌다. */
    val activeKey: String?,
    /** 2차(플랜 변경)용 — 잠긴 파일은 못 고치므로 미리 둔다. */
    val pendingPlanCode: String?,
    val pendingAmount: Long?,
    val endReason: SubscriptionEndReason?,
    val endedAt: Instant?,
    val accountErasedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 구매자 나라(ISO-3166 두 글자, 대문자) — 해지·환불 정책을 고르는 열쇠. 없으면 기본 정책. */
    val country: String? = null,
    /** 같은 요청의 재전송이 같은 구독이게 하는 키(`sha256(계정 id + "\n" + Idempotency-Key)`) — 유니크. id 는 시간순이라 요청에서 정해지지 않는다. 옛 줄은 null 일 수 있다. */
    val dedupeKey: String? = null,
    /**
     * 결제사가 갱신까지 직접 하는 구독(Paddle)의 결제사 쪽 id — 있으면 **우리 갱신 작업은 과금하지 않고** 상태는 웹훅이 미러링한다. 결제사 + 이 id 가 유니크.
     */
    val providerSubscriptionId: String? = null,
    /** 마지막으로 반영한 구독 웹훅의 발생 시각 — 이보다 이른 사건은 낡았다(순서가 뒤바뀌어 도착해도 가장 새로운 상태가 남는다). */
    val providerSyncedAt: Instant? = null,
    /** 서버가 결제사에 미리 만든 첫 결제 거래 id(Paddle) - 웹훅의 첫 결제는 이 id 로 이 줄을 찾는다(결제사 + 이 id 유니크). */
    val firstTransactionId: String? = null,
    /**
     * 카드 등록 창을 서버가 결제사에 미리 예약해야 열리는 결제사(카카오페이)의 첫 창 값(`kind` · 모드 · 창 주소들, JSON) - 같은 요청 키의 재전송이 결제사를 다시 부르지 않고 같은 창을 돌려받는다.
     * 창 주소는 비밀이 아니지만 로그에 싣지 않는다. [registrationWindowAt] 은 그 창을 예약한 시각이다([PaymentBillingGateway.registrationWindowLifetime] 안에서만 다시 쓴다).
     */
    val registrationWindow: String? = null,
    val registrationWindowAt: Instant? = null,
) {
    /** 결제사가 갱신을 한다 — 우리 갱신 · 중도 해지 · 플랜 변경 길은 이 구독에 열리지 않는다. */
    val isProviderManaged: Boolean get() = providerSubscriptionId != null

    override fun toString(): String =
        "PaymentSubscription(id=$id, status=$status, plan=$planCode, amount=${amount.amount} ${amount.normalizedCurrency}, seq=$periodSeq, attempt=$attempt)"
}

/** 구독 상태 전이표(스펙 §3) — 표 밖 전이는 버그다([IllegalStateException]). */
object SubscriptionTransitions {
    private val allowed: Map<SubscriptionStatus, Set<SubscriptionStatus>> = mapOf(
        SubscriptionStatus.PENDING to setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.ENDED),
        SubscriptionStatus.ACTIVE to setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.CANCELING, SubscriptionStatus.ENDED),
        SubscriptionStatus.PAST_DUE to setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.ENDED),
        // 해지 예약 중에 이미 나간 갱신 승인이 늦게 끝나면 그 기간만큼 늘어난다(CANCELING 그대로)
        SubscriptionStatus.CANCELING to setOf(SubscriptionStatus.CANCELING, SubscriptionStatus.ENDED),
        SubscriptionStatus.ENDED to emptySet(),
    )

    fun isAllowed(from: SubscriptionStatus, to: SubscriptionStatus): Boolean = to in allowed.getValue(from)

    fun require(from: SubscriptionStatus, to: SubscriptionStatus) {
        check(isAllowed(from, to)) { "Subscription status transition $from -> $to is not allowed" }
    }
}

/**
 * 구독 주기 — `P1M` 같은 달력 주기([Period]) 또는 `PT3M` 같은 시간 주기([Duration]). 기간 끝은 **첫 결제 시각에서 n 주기**로 센다(UTC 달력 — 그달에 없는 날은 말일로).
 */
class SubscriptionPeriod private constructor(private val calendar: Period?, private val clock: Duration?) {
    /** [anchor] 에서 [cycles] 주기가 지난 시각. */
    /** 달력 주기가 정확히 몇 개월인가(`P1Y` = 12, `P3M` = 3) — 일 단위가 섞였거나 시간 주기면 null. */
    val calendarMonths: Int? get() = calendar?.takeIf { it.days == 0 }?.toTotalMonths()?.toInt()

    fun after(anchor: Instant, cycles: Int): Instant =
        if (calendar != null) anchor.atZone(ZoneOffset.UTC).plus(calendar.multipliedBy(cycles)).toInstant() else anchor.plus(clock!!.multipliedBy(cycles.toLong()))

    companion object {
        /** 잘못된 문자열 · 0 이하의 주기는 [IllegalArgumentException]. */
        fun parse(text: String): SubscriptionPeriod {
            val calendar = runCatching { Period.parse(text) }.getOrNull()
            if (calendar != null) {
                require(!calendar.isZero && !calendar.isNegative) { "A subscription period must be positive" }
                return SubscriptionPeriod(calendar, null)
            }
            val clock = runCatching { Duration.parse(text) }.getOrNull() ?: throw IllegalArgumentException("A subscription period must be an ISO-8601 period or duration (P1M, PT3M)")
            require(!clock.isZero && !clock.isNegative) { "A subscription period must be positive" }
            return SubscriptionPeriod(null, clock)
        }
    }
}
