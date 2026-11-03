package dev.sumin.skeleton.booking

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentNothingToRefundException
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundNotAllowedException
import dev.sumin.skeleton.payment.PaymentRefunds

/** 작업 큐의 종류 - 돈만 받고 자리를 못 준 주문(늦은 결제 · 같은 보류의 둘째 결제 · 금액 불일치)을 환불한다 */
const val BOOKING_REFUND_JOB = "booking-refund"

private val PAYLOAD = Regex("""\{"orderId":"([A-Za-z0-9_.:-]{1,64})","reason":"(NOT_DELIVERED|DUPLICATE)"}""")

/** [BookingRefundQueue] 를 작업 큐에 싣는다 - [JobQueue.enqueue] 는 부르는 쪽 트랜잭션(보류 줄을 바꾼 그 트랜잭션)에 참여한다 */
class JobBookingRefundQueue(private val queue: JobQueue) : BookingRefundQueue {
    override fun enqueue(orderId: String, reason: String) {
        queue.enqueue(BOOKING_REFUND_JOB, """{"orderId":"$orderId","reason":"$reason"}""")
    }
}

/**
 * `booking-refund` 작업 - 주문 전액을 환불한다. 키가 주문 id 라 몇 번 돌아도 환불 줄은 하나다(`PaymentRefunds.refund` 의 멱등 키).
 * 큐 워커는 처리기를 트랜잭션으로 감싸지 않는다 - 결제사를 부르는 이 일은 락 · 트랜잭션 밖이어야 한다. 던지면 큐가 백오프로 다시 시도하고 끝내 못 하면 DEAD 로 경보가 난다.

 * 돌려줄 돈이 없거나(결제사를 안 거친 주문) 이미 전액 환불된 주문은 정상으로 끝난다 - 아니면 정상 경로에서 DEAD 가 되어 거짓 경보가 난다.
 * 환불 사유는 결제가 이미 아는 `NOT_DELIVERED`(자리를 못 줬다) · `DUPLICATE`(같은 보류의 둘째 결제)다.
 */
class BookingRefundJobHandler(private val refunds: PaymentRefunds) : JobHandler {
    override val type: String = BOOKING_REFUND_JOB

    override fun handle(job: Job) {
        val match = PAYLOAD.matchEntire(job.payloadJson) ?: throw PermanentJobFailureException("booking refund job ${job.id} has an unreadable payload")
        val (orderId, reason) = match.destructured
        try {
            refunds.refund(OPERATOR, orderId, reason, null, IdempotencyKey(orderId))
        } catch (_: PaymentNothingToRefundException) {
            // 결제사를 안 거친 주문(결제 면제 · 100% 쿠폰)은 돌려줄 돈이 없다 - 할 일이 끝난 것이다
        } catch (rejected: PaymentRefundNotAllowedException) {
            // 이미 전액 환불된 주문도 끝난 것이다(다른 길로 돌려줬다). 그 밖의 거절(환불할 수 없는 상태 · 정산 불일치)은 진짜 실패라 그대로 던진다
            if (refunds.view(orderId).order.status != PaymentOrderStatus.REFUNDED) throw rejected
        }
    }

    private companion object {
        const val OPERATOR = "system:booking"
    }
}
