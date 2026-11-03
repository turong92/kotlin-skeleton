package dev.sumin.skeleton.video

import java.time.Instant

/** `claim` 응답 — 일꾼은 이것만 보고 일한다. 입력은 remux = 서버가 서명한 읽기 주소(일꾼은 저장소 키를 갖지 않는다), 호환 · 사다리 = 원본 단 재생 목록 주소 */
data class VideoJob(
    /** 영상 id 가 곧 작업 id — 이후 모든 호출의 경로에 쓴다 */
    val jobId: String,
    val lane: VideoLane,
    val attempt: Int,
    val leaseUntil: Instant,
    val inputUrl: String,
    val inputBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val codec: String?,
    /** 일꾼이 임시 공간을 가늠하는 값 — 호환 단 21MB/분 · 사다리 32MB/분 · remux 는 입력 크기 */
    val expectedOutputBytes: Long,
    /** 사다리 차선만 — 서버가 정한 HLS 빌드 폴더 id(32 hex) */
    val build: String?,
    /** 호환 · 사다리 차선만 — 읽을 원본 단 폴더 이름 `o-<해시>`. 마스터 목록에 원본 단을 끼울 때 쓴다 */
    val origFolder: String?,
    /** remux 차선만 — true 면 올리기 전에 모든 프레임을 디코드해 본다(공개 영상, `skeleton.video.verify-public`) */
    val verifyDecode: Boolean,
)

data class UploadUrl(val name: String, val method: String, val url: String, val headers: Map<String, String>, val expiresAt: Instant)

/** 결과 파일 한 개 — 이름은 허용 패턴, 크기는 서버가 폴더를 나열해 대조한다 */
data class ResultFile(val name: String, val bytes: Long, val sha256: String? = null, val width: Int? = null, val height: Int? = null)

/** 일꾼의 ffprobe 결과 — [width] · [height] 는 회전을 적용한 화면 크기, [codec] 은 `avc1.640028` 같은 코덱 문자열 */
data class ProbeResult(
    val container: String?,
    val codec: String?,
    val width: Int,
    val height: Int,
    val rotation: Int?,
    val durationMs: Long,
    val fps: Double?,
    val bitrate: Long?,
    val audioCodec: String?,
)

data class LadderRung(val name: String, val height: Int, val bandwidth: Long)

/** `complete` 본문 — 차선은 어떤 칸이 있느냐로 안다: [orig] = remux, [compat] = 호환 단, [ladderBuild] = 사다리 */
data class VideoJobResult(
    val orig: ResultFile? = null,
    val poster: ResultFile? = null,
    val compat: ResultFile? = null,
    val probe: ProbeResult? = null,
    /** remux 결과가 이미 호환 조건이라 호환 단을 건너뛸 수 있는가 */
    val compatible: Boolean? = null,
    val ladderBuild: String? = null,
    val rungs: List<LadderRung>? = null,
)

/**
 * 호환 · 사다리 일꾼이 읽을 **원본 단 재생 목록 주소**를 만드는 자리. 기본 구현([StorageVideoWorkerInputs])은 공개 영상이면 공개 주소,
 * 비공개 영상이면 일꾼 작업 수명의 출입증이 든 **서버 경로**(`/api/v1/videos/play/<토큰>/o-<해시>/index.m3u8`)를 준다 — 일꾼이 claim 을 부른 서버 주소를 기준으로 푼다
 */
fun interface VideoWorkerInputs {
    fun originalPlaylistUrl(video: Video, ttl: java.time.Duration): String
}
