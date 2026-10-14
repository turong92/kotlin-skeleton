package dev.sumin.skeleton.app.nativecheck

import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
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
    private val json: tools.jackson.databind.ObjectMapper,
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
    fun enqueue(@RequestBody body: PingPayload): Map<String, Long> = mapOf("jobId" to jobs.enqueue("native-check-ping", json.writeValueAsString(JobNote(body.note))))

    @GetMapping("/jobs/handled")
    fun handled(): Map<String, Any?> = mapOf("count" to handler.count.get(), "lastNote" to handler.lastNote)
}

data class AuthenticateRequest(val paymentKey: String, val orderId: String, val amount: Long)

data class PingPayload(val note: String)

/** 작업 일감의 본문 — 컨트롤러 밖에서 JSON 으로 바꾸는 타입이라 네이티브에서는 등록 표시가 있어야 한다 (docs/native-image.md "리플렉션 규칙") */
data class JobNote(val note: String)

@Component
@RegisterReflectionForBinding(JobNote::class)   // 작업 본문(JSON)을 컨트롤러 밖에서 읽고 쓰는 타입 — 이 표시가 없으면 네이티브에서 직렬화가 깨진다
class PingJobHandler(private val json: tools.jackson.databind.ObjectMapper) : JobHandler {
    override val type = "native-check-ping"
    val count = AtomicInteger()

    @Volatile
    var lastNote: String? = null

    override fun handle(job: Job) {
        lastNote = json.readValue(job.payloadJson, JobNote::class.java).note
        count.incrementAndGet()
    }
}
