package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StoragePublicProperties
import dev.sumin.skeleton.storage.StorageWebProperties
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
import dev.sumin.skeleton.video.VideoPlaylists
import dev.sumin.skeleton.video.VideoProperties
import dev.sumin.skeleton.video.VideoStatus
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 로그인 없이 여는 출입증 재생 목록 문과 운영자 삭제 문 */
class VideoPlaybackWebTest {
    private val time = TimeProvider { PlaybackFixtures.t0 }
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private val followUps = FakeFollowUps()
    private val props = VideoProperties(enabled = true)
    private val tokens = VideoPlaybackTokens(PlaybackFixtures.SECRET, time)
    private val playlists = VideoPlaylists(tokens, store, VideoKeys(props, StorageWebProperties()), storage)
    private val playback = StorageVideoPlayback(props, VideoKeys(props, StorageWebProperties()), storage, { StoragePublicProperties() }, tokens)
    private val library = VideoLibrary(store, OwnerVideoAccessPolicy, playback, followUps, FakeTransactions(), time)
    private var operator = false
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(VideoPlaylistController(playlists), VideoAdminController(library, VideoOperatorPolicy { operator }))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private val folder = "videos/p/vid1/"
    private fun token() = tokens.issue("vid1", Duration.ofHours(4)).value
    private fun caller(id: String) = TestingAuthenticationToken(id, "n/a", "ROLE_USER").apply { isAuthenticated = true }

    init {
        store.rows["vid1"] = PlaybackFixtures.video()
        storage.texts["$folder${PlaybackFixtures.ORIG}/index.m3u8"] = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4.0,\ns00000.m4s\n"
    }

    @Test
    fun `the playlist is open without a login, never cached, and carries presigned segment addresses`() {
        val body = mvc.perform(get("/api/v1/videos/play/${token()}/${PlaybackFixtures.ORIG}/index.m3u8"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/vnd.apple.mpegurl"))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andReturn().response.contentAsString

        assertTrue("http://s3.test/${folder}${PlaybackFixtures.ORIG}/s00000.m4s?sig=1" in body, body)
    }

    @Test
    fun `a forged or expired token is 404 and signs nothing`() {
        mvc.perform(get("/api/v1/videos/play/v1.vid1.4102444800.forged/${PlaybackFixtures.ORIG}/index.m3u8"))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("VIDEO.NOT_FOUND"))
        mvc.perform(get("/api/v1/videos/play/garbage/${PlaybackFixtures.ORIG}/index.m3u8")).andExpect(status().isNotFound)

        assertTrue(storage.signedDownloads.isEmpty())
    }

    @Test
    fun `an operator deletes any video with a reason - others get 403, a missing video is 404, a stranger is 401`() {
        mvc.perform(delete("/api/v1/videos/admin/vid1").param("reason", "REPORTED")).andExpect(status().isUnauthorized)
        mvc.perform(delete("/api/v1/videos/admin/vid1").principal(caller("acc_z")).param("reason", "REPORTED")).andExpect(status().isForbidden)
        assertEquals(VideoStatus.PLAYABLE, store.rows.getValue("vid1").status)

        operator = true
        mvc.perform(delete("/api/v1/videos/admin/nope").principal(caller("acc_z")).param("reason", "REPORTED")).andExpect(status().isNotFound)
        mvc.perform(delete("/api/v1/videos/admin/vid1").principal(caller("acc_z")).param("reason", "bad reason!")).andExpect(status().isBadRequest)
        mvc.perform(delete("/api/v1/videos/admin/vid1").principal(caller("acc_z")).param("reason", "REPORTED")).andExpect(status().isNoContent)

        assertEquals(VideoStatus.DELETED, store.rows.getValue("vid1").status)
        assertEquals(listOf(VideoFinalizeEvent.DELETED), followUps.events("vid1"))
    }
}
