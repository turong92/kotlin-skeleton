package dev.sumin.skeleton.payment

import java.time.Instant

/** `payment` 가 장부에 넣는 지연 작업의 종류(= 작업 큐의 `type`). 환불 · 웹훅 작업이 같은 곳에 더해진다. */
object PaymentTaskTypes {
    /** 주문을 만들 때. `runAt = checkout_expires_at` — 아직 `PENDING` 이면 결제사에 물어 닫는다. */
    const val ORDER_EXPIRE = "payment-order-expire"

    /** 승인을 선점할 때. `runAt = 지금 + reconcile.first-delay` — 아직 `CONFIRMING` 이면 결제사에 물어 끝낸다. */
    const val ORDER_RECONCILE = "payment-order-reconcile"

    /** 주문이 `PAID` 가 될 때 — 앱의 `PaymentOrderListener.onPaid` 를 한 번 돈다. */
    const val ORDER_FULFIL = "payment-order-fulfil"

    /** 환불을 `PENDING` 으로 올릴 때. `runAt = 지금 + refund.settle-delay` — 아직 `PENDING` 이면 결제사에 물어 정산한다. 대상 id 는 환불 줄 id. */
    const val REFUND_RECONCILE = "payment-refund-reconcile"

    /** 환불이 `SUCCEEDED` 가 될 때 — 앱의 `PaymentOrderListener.onRefunded`(회수)를 한 번 돈다. 대상 id 는 환불 줄 id. */
    const val REFUND_APPLY = "payment-refund-apply"

    /** 웹훅을 받을 때(수신함 줄과 같은 트랜잭션). 대상 id 는 수신함 줄 id(숫자) — 결제사에 조회해 맞춘다. 재전송(상한 안에서)과 운영자의 다시 처리는 대기 없이 새 작업을 넣는다. 상한에 걸린 신호의 표식 줄은 창 끝에 도는 작업 하나를 가진다. */
    const val WEBHOOK = "payment-webhook"

    /** 구독이 기간을 넘길 때(첫 결제 · 갱신 지급 때). `runAt` = 기간 끝 — 갱신 과금 · 해지 예약의 종료 · 첫 결제 확인을 한 작업이 맡는다. 대상 id 는 구독 id. */
    const val SUBSCRIPTION_RENEW = "payment-subscription-renew"

    /** 구독이 끝날 때 빌링키가 남아 있으면. 결제사에서 빌링키를 지우고 줄의 빌링키 · 고객 키를 비운다. 대상 id 는 구독 id. */
    const val SUBSCRIPTION_REVOKE = "payment-subscription-revoke"

    /** 구독을 중도 해지로 끝낼 때. 보내지 못한 환불이 있으면 다시 보낸다(보낸 줄은 건너뛴다). `runAt` = 지금 + reconcile.first-delay. 대상 id 는 구독 id. */
    const val SUBSCRIPTION_CANCEL_REFUND = "payment-subscription-cancel-refund"
}

/** [subjectId] 는 작업이 가리키는 줄의 id(주문 id, 환불 작업은 환불 줄 id). 작업의 내용은 그 줄을 다시 읽어 정한다 — 작업에 상태를 싣지 않는다. */
data class PaymentTask(
    val type: String,
    val subjectId: String,
    /** null 이면 지금. */
    val runAt: Instant? = null,
    /** null 이면 큐의 기본값. */
    val maxAttempts: Int? = null,
)

/**
 * 지연 작업 넣기. **부르는 쪽 트랜잭션([PaymentLedgerStore.inTransaction])에 참여한다** — 줄이 커밋되면 작업도 있고, 롤백되면 함께 사라진다.
 * (구현 `payment-jdbc` 는 `job-queue-jdbc` 의 `JobQueue.enqueue` 를 부른다 — 그쪽이 부르는 쪽 트랜잭션에 참여한다.)
 */
fun interface PaymentTasks {
    fun enqueue(task: PaymentTask)
}

/** 큐가 작업을 돌려줄 때 처리기에 건네는 것. [attempt] 는 이번이 몇 번째 시도인가(1부터), 마지막 시도면 [lastAttempt]. */
data class PaymentTaskRun(
    val type: String,
    val subjectId: String,
    val attempt: Int,
    val maxAttempts: Int,
) {
    val lastAttempt: Boolean get() = attempt >= maxAttempts
}

/**
 * 작업 종류 하나의 처리기. 환불(B5) · 웹훅(B6)이 자기 처리기를 빈으로 더한다 — [PaymentTaskRunner] 는 처리기를 모아 종류로 나눠 준다.
 * 재시도되므로 **멱등**하게 쓴다.
 */
interface PaymentTaskHandler {
    val type: String

    /** 끝났으면 정상 반환, 다시 해야 하면 [PaymentTaskRetryException], 해 봐야 소용없으면 [PaymentTaskPermanentException]. */
    fun handle(run: PaymentTaskRun)

    /**
     * 이 종류의 작업이 **죽었다**(DEAD) — 큐가 더는 부르지 않는다. 마지막 시도가 끝난 뒤 · 영구 실패 뒤 · **처리기가 마지막 시도에서 DB 오류로 던진 뒤 · 워커가 죽어 멈춘 채 시도를 다 쓴 뒤**
     * (이 길에서는 [handle] 이 마지막 시도를 돌지 못했다)에 **최대 한 번** 불린다 — DEAD 를 커밋한 뒤 알림 전에 프로세스가 죽거나 고리가 DB 오류로 던지면 안 불린다.
     * 마지막 안전망은 운영자 목록의 오래된 `CONFIRMING` 주문 · `PENDING` 환불, 미지급 `PAID` 계열 주문 · 미회수 `SUCCEEDED` 환불이다(HTTP 는 B7). 줄에 "사람이 봐야 한다"는 표시가 없으면 여기서 찍는다 — [handle] 안의 마지막 시도 처리에 기대면 위의 길에서 놓친다.
     *
     * **불리는 곳**: 큐 워커 스레드, **트랜잭션 밖**(DEAD 로 적는 문장이 이미 끝난 뒤). 락이 필요하면 [PaymentLedgerStore.inTransaction] 을 직접 연다. 결제사를 부르지 않는다.
     * **멱등하게 쓴다**(두 번 불려도 한 번만 찍는다). 던져도 큐는 로그만 남기고 계속 돈다(그 줄은 표시 없이 남으므로 던지지 않는 편이 낫다).
     * 기본은 아무것도 하지 않는다 — 표시할 것이 없는 작업(작업 줄 자체가 기록인 것)은 그대로 둔다.
     */
    fun onDead(run: PaymentTaskRun) {}
}

/**
 * 큐 어댑터(`payment-jdbc`)가 부르는 입구. [types] 마다 `JobHandler` 하나를 등록하고 `handle(job)` 이 [run] 을 부르면 된다.
 * - [PaymentTaskPermanentException] → 큐의 영구 실패(`PermanentJobFailureException`)로 옮긴다(재시도하지 않고 바로 죽인다)
 * - 그 밖의 예외(여기서는 [PaymentTaskRetryException])는 그대로 던져 큐의 재시도 · 백오프에 맡긴다
 * `payment` 는 job-queue 의 예외를 모른다.
 *
 * **큐 워커는 처리기를 트랜잭션으로 감싸지 않는다 — 어댑터도 감싸지 않는다.** 결제사를 부르는 처리기는 락 · 트랜잭션 밖이어야 하기 때문이다
 * (바깥 트랜잭션 안에서 불리면 처리기가 [IllegalStateException] 을 던진다 — [PaymentLedgerStore.hasOpenTransaction]). 처리기를 `@Transactional` 로 감싸지 않는다.
 */
interface PaymentTaskRunner {
    val types: Set<String>

    fun run(run: PaymentTaskRun)

    /** 작업이 죽었다 — 종류의 처리기([PaymentTaskHandler.onDead])에 넘긴다. 큐 어댑터가 **DEAD 가 되는 모든 길**에서 부른다. 모르는 종류는 무시. 기본은 아무것도 하지 않는다(앱이 만든 러너를 깨지 않게). */
    fun onDead(run: PaymentTaskRun) {}
}

/** 다시 해 볼 만하다(아직 끝나지 않았다 · 결제사가 지금 대답하지 못한다). 메시지는 **우리가 쓴 문장과 id 뿐**이다 — 작업 줄의 `last_error` 에 남는다. */
class PaymentTaskRetryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 다시 해 봐야 소용없다(모드 · 금액 불일치 · 그 능력이 없는 결제사). 큐가 바로 죽이고 경보를 낸다. 메시지는 우리가 쓴 문장과 id 뿐이다. */
class PaymentTaskPermanentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
