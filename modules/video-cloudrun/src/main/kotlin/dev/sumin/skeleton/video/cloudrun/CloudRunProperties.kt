package dev.sumin.skeleton.video.cloudrun

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Cloud Run 작업(Job)으로 호환 · 사다리 일꾼을 깨운다. **기본 꺼짐** - 켜지 않아도 영상은 상주 일꾼(홈서버 컨테이너 등)이 다 한다.
 * ```yaml
 * skeleton:
 *   video:
 *     cloud-run:
 *       enabled: true
 *       project: my-project        # 구글 클라우드 프로젝트 id
 *       region: asia-northeast3
 *       job: video-worker          # 작업(Job) 이름 - MODE=once 로 정의한 것
 *       credentials-json: ${...}   # 그 작업 하나만 실행하는 서비스 계정 키(JSON 원문) - 운영자 비밀, 환경변수로만
 * ```
 */
@ConfigurationProperties("skeleton.video.cloud-run")
data class CloudRunProperties(
    val enabled: Boolean = false,
    val project: String = "",
    val region: String = "",
    val job: String = "",
    /** 서비스 계정 키 JSON 원문 - **운영자 비밀**. 로그 · 예외 · toString 어디에도 나오지 않는다 */
    val credentialsJson: String = "",
    /** 작업 실행 API 주소 - 시험이 가짜 서버로 바꾼다 */
    val apiBase: String = "https://run.googleapis.com",
    /** 접근 토큰을 받는 주소 - 키 파일 안의 `token_uri` 는 읽지 않는다(키 파일이 나가는 주소를 정하지 못하게). 시험이 가짜 서버로 바꾼다 */
    val tokenUri: String = "https://oauth2.googleapis.com/token",
    /** 구글 서버 한 번 호출(토큰 · 실행)의 시간 제한 */
    val timeout: Duration = Duration.ofSeconds(10),
    /** 이 시간 안에 받아들여진 깨우기를 「실행 중」으로 센다 - 실행은 큐가 빌 때까지 집고 끝난다 */
    val executionWindow: Duration = Duration.ofMinutes(10),
    /** 창 안에 띄울 수 있는 실행 수의 상한 */
    val maxExecutions: Int = 3,
    /** 연달아 깨우지 않는 간격 - 같은 일을 두 실행이 집어도 임대 때문에 안전하지만 비용이다 */
    val minWakeGap: Duration = Duration.ofSeconds(30),
    /** 연속 이만큼 실패하면 `VIDEO_WAKE_FAILED` 경보 */
    val alertAfterFailures: Int = 3,
) {
    init {
        require(maxExecutions >= 1) { "skeleton.video.cloud-run.max-executions must be at least 1" }
        require(alertAfterFailures >= 1) { "skeleton.video.cloud-run.alert-after-failures must be at least 1" }
        require(!executionWindow.isNegative && !executionWindow.isZero) { "skeleton.video.cloud-run.execution-window must be positive" }
        require(!minWakeGap.isNegative) { "skeleton.video.cloud-run.min-wake-gap must not be negative" }
        require(!timeout.isNegative && !timeout.isZero) { "skeleton.video.cloud-run.timeout must be positive" }
    }

    override fun toString(): String = "CloudRunProperties(enabled=$enabled, project=$project, region=$region, job=$job, maxExecutions=$maxExecutions)"
}
