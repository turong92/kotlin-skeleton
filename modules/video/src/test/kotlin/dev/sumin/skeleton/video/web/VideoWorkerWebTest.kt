package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.video.FakeFollowUps
import dev.sumin.skeleton.video.FakeStorage
import dev.sumin.skeleton.video.FakeTransactions
import dev.sumin.skeleton.video.FakeVideoStore
import dev.sumin.skeleton.video.FakeVideoWorkerStore
import dev.sumin.skeleton.video.NoopWaker
import dev.sumin.skeleton.video.OwnerVideoAccessPolicy
import dev.sumin.skeleton.video.VideoJobs
import dev.sumin.skeleton.video.VideoKeys
import dev.sumin.skeleton.video.VideoLane
import dev.sumin.skeleton.video.VideoProperties
import dev.sumin.skeleton.video.VideoQuota
import dev.sumin.skeleton.video.VideoStatus
import dev.sumin.skeleton.video.VideoUploads
import dev.sumin.skeleton.video.VideoWorkerInputs
import dev.sumin.skeleton.video.VideoWorkerService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 일꾼 API 의 문 — 일꾼 토큰만 들어오고, 사용자 로그인과 서로 못 쓴다. 계약의 모양(경로 · 요청 · 응답)이 K4 일꾼 앱이 맞추는 것이다 */
class VideoWorkerWebTest {
    private val t0 = Instant.parse("2026-11-04T00:00:00Z")
    private val props = VideoProperties(enabled = true)
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private val workerStore = FakeVideoWorkerStore()
    private val workers = VideoWorkerService(workerStore, TimeProvider { t0 })
    private val time = TimeProvider { t0 }
    private val jobs = VideoJobs(
        props, store, storage, VideoKeys(props, StorageWebProperties()), VideoWorkerInputs { v, _ -> "https://cdn.example.com/${v.origKey}" },
        FakeFollowUps(), FakeTransactions(), NoopWaker, time,
    )
    private val uploads = VideoUploads(props, store, storage, OwnerVideoAccessPolicy, VideoQuota { _, _, _ -> true }, time)
    private var operator = false
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(VideoWorkerController(jobs), VideoController(uploads), VideoWorkerAdminController(workers, VideoOperatorPolicy { operator }))
        .setCustomArgumentResolvers(VideoWorkerResolver(workers))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private val all = workers.issue("home", VideoLane.entries.toSet())
    private val remuxOnly = workers.issue("remux", setOf(VideoLane.REMUX))

    private fun bearer(token: String) = "Bearer $token"

    private fun queued(): String {
        val id = uploads.start("acc_a", 40_000_000, "video/mp4", "note", "PRIVATE").videoId
        store.rows[id] = store.rows.getValue(id).copy(status = VideoStatus.QUEUED)
        return id
    }

    // ---------- 문 ----------

    @Test
    fun `no token, a wrong token, a revoked token and a user login token are all 401`() {
        val revoked = workers.issue("old", VideoLane.entries.toSet()).also { workers.revoke(it.worker.id) }
        listOf<String?>(null, "Basic abc", bearer("nope"), bearer(revoked.token), bearer("eyJhbGciOiJIUzI1NiJ9.e30.signature")).forEach { header ->
            val req = post("/api/internal/video/claim").contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1"}""")
            if (header != null) req.header("Authorization", header)
            mvc.perform(req).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("VIDEO_WORKER.UNAUTHORIZED"))
        }
    }

    @Test
    fun `a worker token does not open the user API - it is 401 there`() {
        mvc.perform(get("/api/v1/videos").header("Authorization", bearer(all.token))).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/videos").header("Authorization", bearer(all.token)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a lane outside the token is 403`() {
        val id = queued()
        mvc.perform(
            post("/api/internal/video/jobs/$id/complete").header("Authorization", bearer(remuxOnly.token)).contentType(MediaType.APPLICATION_JSON)
                .content("""{"workerId":"w1","compat":{"name":"c-0123456789abcdef/index.m3u8","bytes":1}}"""),
        ).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("VIDEO_WORKER.LANE_FORBIDDEN"))
    }

    // ---------- 계약 ----------

    @Test
    fun `claim answers 204 with nothing queued and the job when there is one`() {
        mvc.perform(post("/api/internal/video/claim").header("Authorization", bearer(all.token)).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1","lanes":["REMUX","COMPAT"]}"""))
            .andExpect(status().isNoContent)

        val id = queued()
        mvc.perform(post("/api/internal/video/claim").header("Authorization", bearer(all.token)).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1","lanes":["REMUX"]}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.jobId").value(id))
            .andExpect(jsonPath("$.value.lane").value("REMUX"))
            .andExpect(jsonPath("$.value.attempt").value(1))
            .andExpect(jsonPath("$.value.inputUrl").exists())
            .andExpect(jsonPath("$.value.inputBytes").value(40_000_000))
            .andExpect(jsonPath("$.value.expectedOutputBytes").exists())
            .andExpect(jsonPath("$.value.verifyDecode").value(false))
            .andExpect(jsonPath("$.value.leaseUntil").exists())
    }

    @Test
    fun `claim refuses an unknown lane name and a bad worker id`() {
        mvc.perform(post("/api/internal/video/claim").header("Authorization", bearer(all.token)).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1","lanes":["FLY"]}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VIDEO_WORKER.INVALID"))
        mvc.perform(post("/api/internal/video/claim").header("Authorization", bearer(all.token)).contentType(MediaType.APPLICATION_JSON).content("""{}"""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `heartbeat, upload-urls, complete errors and fail follow the contract`() {
        val id = queued()
        val auth = bearer(all.token)
        mvc.perform(post("/api/internal/video/claim").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1"}""")).andExpect(status().isOk)

        mvc.perform(post("/api/internal/video/jobs/$id/heartbeat").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1","progress":30}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.leaseUntil").exists())
        assertEquals(30, store.rows.getValue(id).progress)

        mvc.perform(
            post("/api/internal/video/jobs/$id/upload-urls").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"workerId":"w1","names":["o-0123456789abcdef/index.m3u8","p-0123456789abcdef.jpg"]}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(2))
            .andExpect(jsonPath("$.values[0].name").value("o-0123456789abcdef/index.m3u8"))
            .andExpect(jsonPath("$.values[0].method").value("PUT"))
            .andExpect(jsonPath("$.values[0].url").exists())
            .andExpect(jsonPath("$.values[0].headers").exists())
            .andExpect(jsonPath("$.values[0].expiresAt").exists())
        mvc.perform(
            post("/api/internal/video/jobs/$id/upload-urls").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"workerId":"w1","names":["../x"]}"""),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VIDEO_WORKER.PATH_NOT_ALLOWED"))

        mvc.perform(post("/api/internal/video/jobs/$id/heartbeat").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"other"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("VIDEO_WORKER.LEASE_LOST"))

        mvc.perform(post("/api/internal/video/jobs/$id/fail").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1","code":"CORRUPT","detail":"x","retryable":false}"""))
            .andExpect(status().isNoContent)
        assertEquals(VideoStatus.FAILED, store.rows.getValue(id).status)
    }

    @Test
    fun `complete binds the JSON a worker sends and makes the video playable`() {
        val id = queued()
        val auth = bearer(all.token)
        mvc.perform(post("/api/internal/video/claim").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1"}""")).andExpect(status().isOk)
        val hash = "0123456789abcdef"
        listOf("index.m3u8" to 10L, "init.mp4" to 20L, "s00000.m4s" to 300L).forEach { (n, size) -> storage.objects["videos/p/$id/o-$hash/$n"] = size }
        storage.objects["videos/p/$id/p-$hash.jpg"] = 50

        mvc.perform(
            post("/api/internal/video/jobs/$id/complete").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(
                """
                {"workerId":"w1","orig":{"name":"o-$hash/index.m3u8","bytes":330,"sha256":"${hash}${"f".repeat(48)}"},
                 "poster":{"name":"p-$hash.jpg","bytes":50,"width":1280,"height":720},
                 "probe":{"container":"mov,mp4","codec":"avc1.640028","width":1080,"height":1920,"rotation":90,"durationMs":180000,"fps":30.0,"bitrate":17000000,"audioCodec":"aac"},
                 "compatible":false}
                """.trimIndent(),
            ),
        ).andExpect(status().isNoContent)

        assertEquals(VideoStatus.PLAYABLE, store.rows.getValue(id).status)
        assertEquals(1080, store.rows.getValue(id).srcWidth)
        mvc.perform(post("/api/internal/video/jobs/$id/complete").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1"}"""))
            .andExpect(status().isBadRequest)   // 어느 차선인지 말하지 않은 결과
    }

    // ---------- 운영자 발급 ----------

    @Test
    fun `only an operator issues, lists and revokes worker tokens - the token is shown once`() {
        val admin = TestingAuthenticationToken("acc_admin", "n/a", "ROLE_ADMIN").apply { isAuthenticated = true }
        mvc.perform(post("/api/v1/videos/admin/workers").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""{"name":"vm","lanes":["REMUX"]}"""))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/videos/admin/workers").contentType(MediaType.APPLICATION_JSON).content("""{"name":"vm","lanes":["REMUX"]}""")).andExpect(status().isUnauthorized)

        operator = true
        val body = mvc.perform(post("/api/v1/videos/admin/workers").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""{"name":"vm","lanes":["REMUX","COMPAT"]}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.name").value("vm"))
            .andExpect(jsonPath("$.value.token").exists())
            .andReturn().response.contentAsString
        val token = Regex(""""token":"([^"]+)"""").find(body)!!.groupValues[1]
        val workerId = Regex(""""id":(\d+)""").find(body)!!.groupValues[1]
        mvc.perform(post("/api/internal/video/claim").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1"}""")).andExpect(status().isNoContent)

        mvc.perform(get("/api/v1/videos/admin/workers").principal(admin)).andExpect(status().isOk).andExpect(jsonPath("$.values[2].name").value("vm")).andExpect(jsonPath("$.values[2].token").doesNotExist())
        mvc.perform(delete("/api/v1/videos/admin/workers/$workerId").principal(admin)).andExpect(status().isNoContent)
        mvc.perform(post("/api/internal/video/claim").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content("""{"workerId":"w1"}""")).andExpect(status().isUnauthorized)
        mvc.perform(delete("/api/v1/videos/admin/workers/$workerId").principal(admin)).andExpect(status().isNotFound)
    }
}
