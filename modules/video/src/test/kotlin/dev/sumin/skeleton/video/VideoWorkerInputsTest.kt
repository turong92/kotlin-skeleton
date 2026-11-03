package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StoragePublicProperties
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.video.PlaybackFixtures.ORIG
import dev.sumin.skeleton.video.PlaybackFixtures.SECRET
import dev.sumin.skeleton.video.PlaybackFixtures.t0
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** 호환 · 사다리 일꾼이 읽을 원본 단 목록 주소 — 공개 영상은 공개 주소, 비공개 영상은 일꾼 작업 수명의 출입증이 든 목록 주소 */
class VideoWorkerInputsTest {
    private val tokens = VideoPlaybackTokens(SECRET, TimeProvider { t0 })
    private val publicOn = StoragePublicProperties(enabled = true, secret = "s".repeat(40), prefix = "public", baseUrl = "https://cdn.example.com")
    private val props = VideoProperties(enabled = true)
    private fun inputs(public: StoragePublicProperties = publicOn) = StorageVideoWorkerInputs(VideoKeys(props, StorageWebProperties(`public` = public)), tokens, { public })

    @Test
    fun `a private video gets its original playlist under a token that lives as long as the worker job`() {
        val url = inputs().originalPlaylistUrl(PlaybackFixtures.video(), Duration.ofHours(2))

        val token = url.removePrefix("/api/v1/videos/play/").substringBefore('/')
        assertEquals("/api/v1/videos/play/$token/$ORIG/index.m3u8", url)
        assertEquals(t0.plus(Duration.ofHours(2)), assertNotNull(tokens.verify(token)).expiresAt)
        assertEquals("vid1", tokens.verify(token)?.videoId)
    }

    @Test
    fun `a public video gets the public address of its original playlist`() {
        assertEquals(
            "https://cdn.example.com/public/v/vid1/$ORIG/index.m3u8",
            inputs().originalPlaylistUrl(PlaybackFixtures.video(visibility = VideoVisibility.PUBLIC), Duration.ofHours(2)),
        )
    }

    @Test
    fun `a public video needs the public area to be on`() {
        assertFailsWith<IllegalStateException> {
            inputs(StoragePublicProperties()).originalPlaylistUrl(PlaybackFixtures.video(visibility = VideoVisibility.PUBLIC), Duration.ofHours(2))
        }
    }
}
