package dev.sumin.skeleton.alert

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener

/**
 * 작업이 재시도를 다 쓰거나(영구 실패 포함) 처리기가 없어 DEAD 가 되면 `JOB_DEAD` — 키는 작업 종류(같은 종류는 간격 안에 접힌다).
 * 사유는 **예외 종류만** 싣는다(메시지에 이메일 · 토큰이 들 수 있다 — 전문은 작업 줄의 `last_error`).
 * job-queue-jdbc 가 클래스패스에 있을 때만 [AlertJobQueueAutoConfiguration] 이 등록한다.
 */
class AlertDeadJobListener(private val alerts: OwnerAlerts) : JobDeadListener {
    override fun onDead(job: Job, reason: String) {
        val exception = reason.substringBefore(':').trim().substringAfterLast('.').ifEmpty { "unknown" }
        alerts.emit(
            BuiltInAlertKind.JOB_DEAD,
            key = job.type,
            detail = "Job ${job.type} #${job.id} stopped after ${job.attempts} attempts ($exception). Check last_error on the job row, then retry or drop it",
            immediate = true,
        )
    }
}
