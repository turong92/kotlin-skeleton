package dev.sumin.skeleton.app.nativecheck

import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 시험 스크립트의 보조 경로. 가짜 결제사의 "결제창에서 인증 끝" 흉내 · 받은 메일 · 작업 넣기/처리 횟수 — 제품 코드가 아니다. */
@RestController
@RequestMapping("/api/v1/native-check")
class NativeCheckController(
    private val mails: MailBox,
    private val gateway: ScriptedGateway,
    private val jobs: JobQueue,
    private val handler: PingJobHandler,
) {
    /** 마지막 메일의 종류 · 확인 코드 · 링크. */
    @GetMapping("/mails/last")
    fun lastMail(@RequestParam to: String, @RequestParam kind: String): Map<String, Any?> {
        val mail: AccountMail = mails.sent.lastOrNull { m: AccountMail -> m.to == to && m.kind.name == kind } ?: return mapOf("found" to false)
        return mapOf("found" to true, "code" to mail.vars["code"], "link" to mail.link)
    }

    @PostMapping("/authenticate")
    fun authenticate(@RequestBody body: AuthenticateRequest): Map<String, Boolean> {
        gateway.authenticate(body.paymentKey, body.orderId, PaymentAmount(body.amount, "KRW"))
        return mapOf("ok" to true)
    }

    @PostMapping("/jobs")
    fun enqueue(@RequestBody body: PingPayload): Map<String, Long> = mapOf("jobId" to jobs.enqueue("native-check-ping", """{"note":"${body.note}"}"""))

    @GetMapping("/jobs/handled")
    fun handled(): Map<String, Any?> = mapOf("count" to handler.count.get(), "lastNote" to handler.lastNote)
}

data class AuthenticateRequest(val paymentKey: String, val orderId: String, val amount: Long)

data class PingPayload(val note: String)

@Component
class PingJobHandler(private val json: tools.jackson.databind.ObjectMapper) : JobHandler {
    override val type = "native-check-ping"
    val count = AtomicInteger()

    @Volatile
    var lastNote: String? = null

    override fun handle(job: Job) {
        lastNote = json.readValue(job.payloadJson, PingPayload::class.java).note
        count.incrementAndGet()
    }
}
