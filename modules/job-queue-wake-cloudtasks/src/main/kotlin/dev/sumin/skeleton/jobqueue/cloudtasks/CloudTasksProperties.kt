package dev.sumin.skeleton.jobqueue.cloudtasks

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 작업 큐의 깨우기(`JobWake`)를 Cloud Tasks 로 예약한다 — 일감이 생기거나 이르러질 때 그 시각에 깨우기 주소(drain)를 한 번 부르게 하는 태스크를 만든다.
 * 인증은 실행 계정의 메타데이터 서버 토큰이다(키 파일 없음). 아래 넷이 다 차야 켜진다 — **실행 때 판정**한다(빈은 늘 있다: 배포 뒤 환경변수로 켜는 이미지가 빌드 때 굳지 않게).
 * ```yaml
 * skeleton:
 *   job-queue-wake-cloudtasks:
 *     project: acme-proj
 *     region: asia-northeast3
 *     queue: acme-drain
 *     target-url: https://acme-api-123456789012.asia-northeast3.run.app/internal/jobs/drain
 * ```
 */
@ConfigurationProperties("skeleton.job-queue-wake-cloudtasks")
data class CloudTasksProperties(
    val project: String = "",
    val region: String = "",
    val queue: String = "",
    /** 태스크가 부를 깨우기 주소 전체(https) */
    val targetUrl: String = "",
    /** Cloud Tasks API 주소 — 시험 · 네이티브 점검이 가짜 서버로 바꾼다. 보호 환경에서는 기본이 아니면 기동 실패 */
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    /** 메타데이터 서버 주소 — 시험 · 네이티브 점검이 가짜 서버로 바꾼다. 보호 환경에서는 기본이 아니면 기동 실패 */
    val metadataUrl: String = DEFAULT_METADATA_URL,
    /** 구글 쪽 한 번 호출(토큰 · 태스크 만들기)의 시간 제한 */
    val timeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "skeleton.job-queue-wake-cloudtasks.timeout must be positive" }
    }

    /** 넷 다 찼나 */
    val configured: Boolean get() = missing().isEmpty()

    /** 비어 있는 설정 이름 — 기동 로그용 */
    fun missing(): List<String> = listOf("project" to project, "region" to region, "queue" to queue, "target-url" to targetUrl)
        .filter { it.second.isBlank() }.map { "skeleton.job-queue-wake-cloudtasks.${it.first}" }

    companion object {
        const val DEFAULT_API_BASE_URL = "https://cloudtasks.googleapis.com"
        const val DEFAULT_METADATA_URL = "http://metadata.google.internal"
    }
}
