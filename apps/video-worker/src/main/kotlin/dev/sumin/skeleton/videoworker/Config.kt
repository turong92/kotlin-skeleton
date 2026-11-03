package dev.sumin.skeleton.videoworker

import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

enum class Mode { POLL, ONCE }

/**
 * 일꾼 설정 — 환경변수 `VIDEO_WORKER_*`. 토큰은 [toString] 에 안 나온다 (로그 · 출력에 남기지 않는다).
 * 위치(홈서버 컨테이너 · Cloud Run · 아무 기계 · 로컬 compose)는 이 설정이 정한다 — 같은 이미지 · 같은 프로그램이다
 */
data class WorkerConfig(
    val apiBase: String,
    val token: String,
    val workerId: String,
    val lanes: Set<String> = setOf("REMUX", "COMPAT", "LADDER", "ANIMATED"),
    val mode: Mode = Mode.POLL,
    val pollInterval: Duration = Duration.ofSeconds(5),
    val heartbeatInterval: Duration = Duration.ofSeconds(30),
    val workDir: Path = Paths.get("/work"),
    /** 로컬 compose — 서명 주소의 출처(게이트웨이 도메인)가 컨테이너 안에서 안 열리므로 이 출처로 바꿔 부른다. Host 헤더는 원래 값을 유지한다 */
    val storageOrigin: String? = null,
    val ffmpeg: String = "ffmpeg",
    val ffprobe: String = "ffprobe",
    /** 양수면 `nice -n` 으로 낮춘 우선순위로 돌린다(다른 일과 기계를 같이 쓸 때) */
    val nice: Int = 0,
    val ffmpegTimeout: Duration = Duration.ofHours(3),
    /** 상주(poll) 모양의 `/health` 포트 — 홈서버 배포 계약의 컨테이너 안 `wget` 검사가 부른다. 0 이면 서버를 안 띄운다. `once` 모양은 띄우지 않는다 */
    val healthPort: Int = 8080,
    /** 일 하나(내려받기 · ffmpeg · 올리기 전부)의 벽시계 한도 — 넘으면 ffmpeg 를 죽이고 재시도 가능한 실패로 알린다. [ffmpegTimeout] 보다 길게 */
    val jobTimeout: Duration = Duration.ofHours(4),
) {
    override fun toString(): String = "WorkerConfig(apiBase=$apiBase, workerId=$workerId, lanes=$lanes, mode=$mode, workDir=$workDir, healthPort=$healthPort)"

    companion object {
        private val LANES = setOf("REMUX", "COMPAT", "LADDER", "ANIMATED")

        fun fromEnv(env: Map<String, String>): WorkerConfig {
            fun need(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("환경변수 $name 이 필요하다")
            val lanes = env["VIDEO_WORKER_LANES"]?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim().uppercase() }?.toSet() ?: LANES
            require(lanes.isNotEmpty() && lanes.all { it in LANES }) { "VIDEO_WORKER_LANES 는 REMUX · COMPAT · LADDER · ANIMATED 중에서 쉼표로" }
            return WorkerConfig(
                apiBase = need("VIDEO_WORKER_API").trimEnd('/'),
                token = need("VIDEO_WORKER_TOKEN"),
                workerId = env["VIDEO_WORKER_ID"]?.takeIf { it.isNotBlank() } ?: "worker-" + (System.getenv("HOSTNAME") ?: "local"),
                lanes = lanes,
                mode = if (env["VIDEO_WORKER_MODE"]?.lowercase() == "once") Mode.ONCE else Mode.POLL,
                pollInterval = Duration.ofSeconds(env["VIDEO_WORKER_POLL_SECONDS"]?.toLongOrNull() ?: 5),
                healthPort = env["VIDEO_WORKER_HEALTH_PORT"]?.toIntOrNull() ?: 8080,
                workDir = Paths.get(env["VIDEO_WORKER_WORKDIR"] ?: "/work"),
                storageOrigin = env["VIDEO_WORKER_STORAGE_ORIGIN"]?.takeIf { it.isNotBlank() }?.trimEnd('/'),
                nice = env["VIDEO_WORKER_NICE"]?.toIntOrNull() ?: 0,
                ffmpegTimeout = Duration.ofMinutes(env["VIDEO_WORKER_FFMPEG_TIMEOUT_MINUTES"]?.toLongOrNull() ?: 180),
                jobTimeout = Duration.ofMinutes(env["VIDEO_WORKER_JOB_TIMEOUT_MINUTES"]?.toLongOrNull() ?: 240),
            )
        }
    }
}
