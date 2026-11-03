package dev.sumin.skeleton.videoworker

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** 서버 `VideoJob` 의 복제 — 필드 이름이 계약이다(서버 쪽 `VideoJobModels.kt`). 응답은 `{value: …}` 봉투 */
data class Job(
    val jobId: String,
    val lane: String,
    val attempt: Int,
    val inputUrl: String,
    val inputBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val codec: String? = null,
    val expectedOutputBytes: Long = 0,
    val build: String? = null,
    /** 호환판 · 사다리 차선만 — 읽을 원본 단 폴더 이름 `o-<해시>`(입력 주소가 그 안의 `index.m3u8`) */
    val origFolder: String? = null,
    /** REMUX 만 — true 면 올리기 전에 모든 프레임을 디코드해 본다(공개 영상 · 서버 설정 `skeleton.video.verify-public`) */
    val verifyDecode: Boolean = false,
)

data class UploadUrl(val name: String, val method: String, val url: String, val headers: Map<String, String> = emptyMap())

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResultFile(val name: String, val bytes: Long, val sha256: String? = null, val width: Int? = null, val height: Int? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
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

data class LadderRungResult(val name: String, val height: Int, val bandwidth: Long)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CompleteBody(
    val workerId: String,
    val orig: ResultFile? = null,
    val poster: ResultFile? = null,
    val compat: ResultFile? = null,
    val probe: ProbeResult? = null,
    val compatible: Boolean? = null,
    val ladderBuild: String? = null,
    val rungs: List<LadderRungResult>? = null,
    /** ANIMATED 차선 - `display.mp4` 또는 `display.webp` */
    val display: ResultFile? = null,
)

/** 임대를 잃었다(409) — 하던 일을 멈춘다 */
class LeaseLostException : RuntimeException("임대를 잃었다")

class ApiException(val status: Int, message: String) : RuntimeException(message)

/** 일꾼 API 클라이언트. 토큰은 헤더로만 — 어디에도 안 찍는다 */
class WorkerApi(private val config: WorkerConfig, private val http: HttpClient = defaultClient()) {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    /** 일이 없으면 null(204) */
    fun claim(lanes: Set<String>): Job? {
        val res = post("/claim", mapOf("workerId" to config.workerId, "lanes" to lanes.toList()))
        return if (res.statusCode() == 204) null else json.treeToValue(json.readTree(res.body())["value"], Job::class.java)
    }

    /** 임대를 늘린다. [progress] 는 0..100(모르면 null) */
    fun heartbeat(jobId: String, progress: Int? = null) {
        post("/jobs/$jobId/heartbeat", listOfNotNull("workerId" to config.workerId, progress?.let { "progress" to it.coerceIn(0, 100) }).toMap())
    }

    fun uploadUrls(jobId: String, names: List<String>): List<UploadUrl> {
        val res = post("/jobs/$jobId/upload-urls", mapOf("workerId" to config.workerId, "names" to names))
        return json.readTree(res.body())["values"].toList().map { json.treeToValue(it, UploadUrl::class.java) }
    }

    fun complete(jobId: String, body: CompleteBody) {
        post("/jobs/$jobId/complete", body)
    }

    fun fail(jobId: String, code: String, detail: String?, retryable: Boolean) {
        post("/jobs/$jobId/fail", mapOf("workerId" to config.workerId, "code" to code, "detail" to detail?.take(400), "retryable" to retryable))
    }

    private fun post(path: String, body: Any): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create("${config.apiBase}/api/internal/video$path"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer ${config.token}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        return when (res.statusCode()) {
            in 200..299 -> res
            // 409 임대를 남이 가졌다 · 404 영상을 지웠거나 운영자가 내렸다 — 둘 다 이 일은 더 하지 않는다
            409, 404 -> throw LeaseLostException()
            else -> throw ApiException(res.statusCode(), "일꾼 API ${res.statusCode()} $path")
        }
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }
}
