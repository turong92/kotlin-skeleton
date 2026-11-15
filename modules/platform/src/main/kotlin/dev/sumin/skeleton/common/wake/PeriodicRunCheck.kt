package dev.sumin.skeleton.common.wake

import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils

/**
 * 주기 작업(`@SkeletonScheduled` 표시 + [OnRequestTask])이 **돌아갈 길이 있는지** 기동 때 확인한다.
 * `scheduler` 모듈이 없으면 표시는 읽는 쪽이 없어 무시되고, 요청 구동 모드(`skeleton.job-queue.mode=on-request`)가 아니면 깨우기도 없다 -
 * 그러면 그 작업은 조용히 한 번도 안 돈다. 켠 기능이 그 작업에 달려 있으면(예: 예약 푸시 · 보관 기한 삭제) 조용히 안 도는 대신 시작을 멈추고 무엇을 하면 되는지 말한다.
 * 모드는 AOT · 네이티브 이미지에서 굳지 않게 실행 때 환경에서 읽는다.
 */
object PeriodicRunCheck {
    private const val SCHEDULED_MARK = "dev.sumin.skeleton.scheduler.SkeletonScheduled"

    /** [feature] 가 기대는 주기 작업이 돌 길이 없으면 [IllegalStateException]. [schedulerPresent] 는 시험용 */
    fun require(
        feature: String,
        environment: Environment,
        schedulerPresent: Boolean = ClassUtils.isPresent(SCHEDULED_MARK, PeriodicRunCheck::class.java.classLoader),
    ) {
        val onRequest = environment.getProperty("skeleton.job-queue.mode")?.replace("-", "")?.replace("_", "")?.equals("onrequest", ignoreCase = true) == true
        check(schedulerPresent || onRequest) {
            "$feature is on, but nothing would run its periodic work: add the scheduler module (implementation(project(\":modules:scheduler\"))) " +
                "or run the request-driven mode (skeleton.job-queue.mode=on-request, docs/cloud-run-scale-to-zero.md) whose wake call runs it"
        }
    }
}
