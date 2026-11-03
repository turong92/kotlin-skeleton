package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StoragePublicProperties
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.storage.UploadedPart
import dev.sumin.skeleton.video.FakeFollowUps
import dev.sumin.skeleton.video.FakeStorage
import dev.sumin.skeleton.video.FakeTransactions
import dev.sumin.skeleton.video.FakeVideoStore
import dev.sumin.skeleton.video.OwnerVideoAccessPolicy
import dev.sumin.skeleton.video.PlaybackFixtures
import dev.sumin.skeleton.video.StorageVideoPlayback
import dev.sumin.skeleton.video.VideoFinalizeEvent
import dev.sumin.skeleton.video.VideoKeys
import dev.sumin.skeleton.video.VideoLibrary
import dev.sumin.skeleton.video.VideoPlaybackTokens
import dev.sumin.skeleton.video.VideoProperties
import dev.sumin.skeleton.video.VideoQuota
import dev.sumin.skeleton.video.VideoStatus
import dev.sumin.skeleton.video.VideoUploads
import kotlin.test.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 올리기 HTTP 모양 — 시작 · 조각 주소 · 조각 목록 · 마침 · 지우기. 남의 영상은 모두 404, 로그인하지 않으면 401 */
class VideoWebTest {
    private val storage = FakeStorage()
    private val store = FakeVideoStore()
    private val followUps = FakeFollowUps()
    private val time = TimeProvider { PlaybackFixtures.t0 }
    private val props = VideoProperties(enabled = true)
    private val uploads = VideoUploads(props, store, storage, OwnerVideoAccessPolicy, VideoQuota { _, _, _ -> true }, time)
    private val playback = StorageVideoPlayback(props, VideoKeys(props, StorageWebProperties()), storage, { StoragePublicProperties() }, VideoPlaybackTokens(PlaybackFixtures.SECRET, time))
    private val library = VideoLibrary(store, OwnerVideoAccessPolicy, playback, followUps, FakeTransactions(), time)
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(VideoController(uploads, library)).setControllerAdvice(GlobalExceptionHandler()).build()

    private fun caller(id: String) = TestingAuthenticationToken(id, "n/a", "ROLE_USER").apply { isAuthenticated = true }

    private fun start(account: String = "acc_a"): String {
        val body = mvc.perform(
            post("/api/v1/videos").principal(caller(account)).contentType(MediaType.APPLICATION_JSON)
                .content("""{"bytes":40000000,"contentType":"video/mp4","purpose":"note","visibility":"PRIVATE"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Regex(""""id":"([^"]+)"""").find(body)!!.groupValues[1]
    }

    @Test
    fun `start answers 201 with the id, the upload id and the part size`() {
        mvc.perform(
            post("/api/v1/videos").principal(caller("acc_a")).contentType(MediaType.APPLICATION_JSON)
                .content("""{"bytes":40000000,"contentType":"video/mp4","purpose":"note","visibility":"PUBLIC"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.uploadId").value("up-1"))
            .andExpect(jsonPath("$.value.partSize").value(16 * 1024 * 1024))
            .andExpect(jsonPath("$.value.partCount").value(3))
    }

    @Test
    fun `an anonymous call is 401 and a file over 4GB is 413 VIDEO_TOO_LARGE`() {
        mvc.perform(post("/api/v1/videos").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)
        mvc.perform(
            post("/api/v1/videos").principal(caller("acc_a")).contentType(MediaType.APPLICATION_JSON)
                .content("""{"bytes":4294967297,"contentType":"video/mp4","purpose":"note","visibility":"PRIVATE"}"""),
        ).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("VIDEO.TOO_LARGE"))
    }

    @Test
    fun `part urls and the list of uploaded parts`() {
        val id = start()
        storage.parts = listOf(UploadedPart(1, "\"e1\"", 16777216))

        mvc.perform(post("/api/v1/videos/$id/parts").principal(caller("acc_a")).contentType(MediaType.APPLICATION_JSON).content("""{"numbers":[2,3]}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.values[0].number").value(2)).andExpect(jsonPath("$.values[0].method").value("PUT"))
            .andExpect(jsonPath("$.values[0].headers").exists()).andExpect(jsonPath("$.values[0].url").value("http://s3.test/videos/u/$id?part=2"))
            .andExpect(jsonPath("$.values.length()").value(2))
        mvc.perform(get("/api/v1/videos/$id/parts").principal(caller("acc_a")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].n").value(1))
            .andExpect(jsonPath("$.values[0].etag").value("\"e1\""))
            .andExpect(jsonPath("$.values[0].bytes").value(16777216))
    }

    @Test
    fun `listing the parts of an upload that is gone or already finished is 404 so the client starts over`() {
        val id = start()
        storage.objects["videos/u/$id"] = 40000000
        mvc.perform(
            post("/api/v1/videos/$id/complete").principal(caller("acc_a")).contentType(MediaType.APPLICATION_JSON)
                .content("""{"parts":[{"n":1,"etag":"a"},{"n":2,"etag":"b"},{"n":3,"etag":"c"}]}"""),
        ).andExpect(status().isOk)
        mvc.perform(get("/api/v1/videos/$id/parts").principal(caller("acc_a"))).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("VIDEO.NOT_FOUND"))
    }

    @Test
    fun `an item carries the fields the screens read - one line of the list is the complete answer`() {
        val id = start()
        mvc.perform(get("/api/v1/videos").param("purpose", "note").principal(caller("acc_a")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].id").value(id))
            .andExpect(jsonPath("$.values[0].purpose").value("note"))
            .andExpect(jsonPath("$.values[0].visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.values[0].status").value("UPLOADING"))
            .andExpect(jsonPath("$.values[0].bytes").value(40000000))
            .andExpect(jsonPath("$.values[0].compat").value("NONE"))
            .andExpect(jsonPath("$.values[0].ladder").value("NONE"))
            .andExpect(jsonPath("$.values[0].progress").doesNotExist())
            .andExpect(jsonPath("$.values[0].failCode").doesNotExist())
            .andExpect(jsonPath("$.values[0].width").doesNotExist())
            .andExpect(jsonPath("$.values[0].createdAt").exists())
    }

    @Test
    fun `complete queues the video and exposes its state`() {
        val id = start()
        storage.objects["videos/u/$id"] = 40000000
        mvc.perform(
            post("/api/v1/videos/$id/complete").principal(caller("acc_a")).contentType(MediaType.APPLICATION_JSON)
                .content("""{"parts":[{"n":1,"etag":"a"},{"n":2,"etag":"b"},{"n":3,"etag":"c"}]}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("QUEUED")).andExpect(jsonPath("$.value.purpose").value("note"))
    }

    @Test
    fun `someone else's video is 404 for parts, listing parts, get and delete`() {
        val id = start("acc_a")
        mvc.perform(post("/api/v1/videos/$id/parts").principal(caller("acc_b")).contentType(MediaType.APPLICATION_JSON).content("""{"numbers":[1]}""")).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/videos/$id/parts").principal(caller("acc_b"))).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("VIDEO.NOT_FOUND"))
        mvc.perform(get("/api/v1/videos/$id").principal(caller("acc_b"))).andExpect(status().isNotFound)
        mvc.perform(delete("/api/v1/videos/$id").principal(caller("acc_b"))).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/videos/$id").principal(caller("acc_a"))).andExpect(status().isOk)
    }

    @Test
    fun `delete answers 204 and the list no longer shows the video`() {
        val id = start()
        mvc.perform(delete("/api/v1/videos/$id").principal(caller("acc_a"))).andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/videos").param("purpose", "note").principal(caller("acc_a"))).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(0))
    }

    @Test
    fun `playback of a playable video answers the grant the player reads, and nothing for someone else's`() {
        store.rows["vid1"] = PlaybackFixtures.video()

        mvc.perform(post("/api/v1/videos/vid1/playback").principal(caller("acc_a")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.src").value(org.hamcrest.Matchers.startsWith("/api/v1/videos/play/v1.vid1.")))
            .andExpect(jsonPath("$.value.compat").exists())
            .andExpect(jsonPath("$.value.hls").exists())
            .andExpect(jsonPath("$.value.poster").exists())
            .andExpect(jsonPath("$.value.codec").value("avc1.640028"))
            .andExpect(jsonPath("$.value.w").value(1080))
            .andExpect(jsonPath("$.value.h").value(1920))
            .andExpect(jsonPath("$.value.durationMs").value(180000))
            .andExpect(jsonPath("$.value.big").value(true))
            .andExpect(jsonPath("$.value.expiresAt").exists())
        mvc.perform(post("/api/v1/videos/vid1/playback").principal(caller("acc_b"))).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("VIDEO.NOT_FOUND"))
        mvc.perform(post("/api/v1/videos/vid1/playback")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `playback of a video that is not playable yet is 409 VIDEO_NOT_READY`() {
        val id = start()

        mvc.perform(post("/api/v1/videos/$id/playback").principal(caller("acc_a")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("VIDEO.NOT_READY"))
    }

    @Test
    fun `delete of a playable video queues the folder cleanup`() {
        store.rows["vid1"] = PlaybackFixtures.video()

        mvc.perform(delete("/api/v1/videos/vid1").principal(caller("acc_b"))).andExpect(status().isNotFound)
        mvc.perform(delete("/api/v1/videos/vid1").principal(caller("acc_a"))).andExpect(status().isNoContent)

        kotlin.test.assertEquals(VideoStatus.DELETED, store.rows.getValue("vid1").status)
        kotlin.test.assertEquals(listOf(VideoFinalizeEvent.DELETED), followUps.events("vid1"))
    }
}
