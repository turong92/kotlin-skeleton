package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.HostedPlanChange
import dev.sumin.skeleton.payment.HostedPlanChangeQuote
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentDeclinedException
import dev.sumin.skeleton.payment.PaymentHostedPlanChangeGateway
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentPlanChangeNotAllowedException
import java.util.concurrent.CopyOnWriteArrayList

/** [FakePushGateway] 에 플랜 변경 능력을 더한 대역 - 결제사가 답할 견적과 적용 결과를 시험이 정한다. 능력이 없는 결제사는 [FakePushGateway] 그대로다. */
class FakeHostedPushGateway(providerId: String = "paddle", mode: PaymentMode = PaymentMode.TEST) : FakePushGateway(providerId, mode), PaymentHostedPlanChangeGateway {
    enum class Apply { APPLIED, DECLINED, NOT_ALLOWED, UNKNOWN }

    /** 미리보기 · 적용이 답하는 견적. */
    @Volatile
    var quote = HostedPlanChangeQuote(charge = PaymentAmount(500, "USD"), credit = PaymentAmount(300, "USD"), recurring = PaymentAmount(1_990, "USD"), periodStart = null, periodEnd = null, restartsPeriod = false)

    @Volatile
    var apply = Apply.APPLIED

    val previews = CopyOnWriteArrayList<HostedPlanChange>()
    val applies = CopyOnWriteArrayList<HostedPlanChange>()

    override fun previewPlanChange(change: HostedPlanChange): HostedPlanChangeQuote {
        previews += change
        return quote
    }

    override fun applyPlanChange(change: HostedPlanChange) {
        applies += change
        when (apply) {
            Apply.APPLIED -> Unit
            Apply.DECLINED -> throw PaymentDeclinedException("subscription_payment_declined")
            Apply.NOT_ALLOWED -> throw PaymentPlanChangeNotAllowedException()
            Apply.UNKNOWN -> throw IllegalStateException("planted timeout")
        }
    }
}
