package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StoragePublicProperties
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.video.PlaybackFixtures.BUILD
import dev.sumin.skeleton.video.PlaybackFixtures.COMPAT
import dev.sumin.skeleton.video.PlaybackFixtures.ORIG
import dev.sumin.skeleton.video.PlaybackFixtures.POSTER
import dev.sumin.skeleton.video.PlaybackFixtures.SECRET
import dev.sumin.skeleton.video.PlaybackFixtures.t0
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 재생 허가(공개 주소 · 비공개 출입증)와 삭제. 남의 영상은 모든 호출이 같은 404 이고, 삭제는 줄을 먼저 닫고 폴더 정리를 후속으로 맡긴다 */
class VideoLibraryTest {
    private val time = TimeProvider { t0 }
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private val followUps = FakeFollowUps()
    private val tx = FakeTransactions()
    private val props = VideoProperties(enabled = true)
    private val web = StorageWebProperties(`public` = StoragePublicProperties(enabled = true, secret = "s".repeat(40), prefix = "public", baseUrl = "https://cdn.example.com"))
    private val tokens = VideoPlaybackTokens(SECRET, time)
    private val playback = StorageVideoPlayback(props, VideoKeys(props, web), storage, { web.`public` }, tokens)
    private val library = VideoLibrary(store, OwnerVideoAccessPolicy, playback, followUps, tx, time)

    private fun put(v: Video) = v.also { store.rows[it.id] = it }

    // ---------- 재생 허가 ----------

    @Test
    fun `a private grant puts the token in the path of every address, signs the poster for the token lifetime and says when it ends`() {
        put(PlaybackFixtures.video())

        val grant = library.playback("acc_a", "vid1")

        val token = grant.src.removePrefix("/api/v1/videos/play/").substringBefore('/')
        assertEquals("vid1", tokens.verify(token)?.videoId)
        assertEquals("/api/v1/videos/play/$token/$ORIG/index.m3u8", grant.src)
        assertEquals("/api/v1/videos/play/$token/$COMPAT/index.m3u8", grant.compat)
        assertEquals("/api/v1/videos/play/$token/h-$BUILD/master.m3u8", grant.hls)
        assertEquals(t0.plus(Duration.ofHours(4)), grant.expiresAt)
        assertEquals("http://s3.test/videos/p/vid1/$POSTER?sig=1", grant.poster)
        assertEquals(listOf("videos/p/vid1/$POSTER"), storage.signedDownloads.map { it.key.value })
        assertEquals(Duration.ofHours(4), storage.signedDownloads.single().expiresIn)
        assertEquals(listOf("avc1.640028", 1080, 1920, 180_000L, true), listOf(grant.codec, grant.w, grant.h, grant.durationMs, grant.big))
    }

    @Test
    fun `a public grant is the plain public addresses - no token, no signing, no expiry`() {
        put(PlaybackFixtures.video(visibility = VideoVisibility.PUBLIC))

        val grant = library.playback(null, "vid1")

        assertEquals("https://cdn.example.com/public/v/vid1/$ORIG/index.m3u8", grant.src)
        assertEquals("https://cdn.example.com/public/v/vid1/$COMPAT/index.m3u8", grant.compat)
        assertEquals("https://cdn.example.com/public/v/vid1/h-$BUILD/master.m3u8", grant.hls)
        assertEquals("https://cdn.example.com/public/v/vid1/$POSTER", grant.poster)
        assertNull(grant.expiresAt)
        assertTrue(storage.signedDownloads.isEmpty())
    }

    @Test
    fun `tracks that are not ready are left out, and an original that is already compatible is not big`() {
        put(PlaybackFixtures.video("skipped", compat = TrackStatus.SKIPPED, ladder = TrackStatus.SKIPPED))
        put(PlaybackFixtures.video("waiting", compat = TrackStatus.QUEUED, ladder = TrackStatus.RUNNING))

        library.playback("acc_a", "skipped").let {
            assertNull(it.compat); assertNull(it.hls); assertFalse(it.big)
        }
        library.playback("acc_a", "waiting").let {
            assertNull(it.compat); assertNull(it.hls); assertTrue(it.big)
        }
    }

    @Test
    fun `someone else's private video, a deleted one and an unknown id are all not found - and nothing is signed`() {
        put(PlaybackFixtures.video())
        put(PlaybackFixtures.video("gone", status = VideoStatus.DELETED))

        assertFailsWith<VideoNotFoundException> { library.playback("acc_b", "vid1") }
        assertFailsWith<VideoNotFoundException> { library.playback(null, "vid1") }
        assertFailsWith<VideoNotFoundException> { library.playback("acc_a", "gone") }
        assertFailsWith<VideoNotFoundException> { library.playback("acc_a", "nope") }
        assertTrue(storage.signedDownloads.isEmpty())
    }

    @Test
    fun `a video that is not playable yet answers 409 not ready`() {
        VideoStatus.entries.filter { it != VideoStatus.PLAYABLE && it != VideoStatus.DELETED }.forEach { status ->
            put(PlaybackFixtures.video("v-${status.name}", status = status))
            assertFailsWith<VideoNotReadyException>(status.name) { library.playback("acc_a", "v-${status.name}") }
        }
        assertEquals("VIDEO.NOT_READY", VideoErrorCode.NOT_READY.code)
    }

    // ---------- 삭제 ----------

    @Test
    fun `the owner deletes - the row closes first and the folder cleanup is queued in the same transaction`() {
        put(PlaybackFixtures.video())

        library.deleteOwn("acc_a", "vid1")

        assertEquals(VideoStatus.DELETED, store.rows.getValue("vid1").status)
        assertEquals(listOf(VideoFinalizeEvent.DELETED), followUps.events("vid1"))
        assertEquals(1, tx.runs)
        assertTrue(storage.deleted.isEmpty(), "objects go in the follow-up, never in the request")
    }

    @Test
    fun `someone else's video and a second delete are not found and queue nothing`() {
        put(PlaybackFixtures.video())

        assertFailsWith<VideoNotFoundException> { library.deleteOwn("acc_b", "vid1") }
        assertEquals(VideoStatus.PLAYABLE, store.rows.getValue("vid1").status)
        library.deleteOwn("acc_a", "vid1")
        assertFailsWith<VideoNotFoundException> { library.deleteOwn("acc_a", "vid1") }
        assertFailsWith<VideoNotFoundException> { library.deleteOwn("acc_a", "nope") }

        assertEquals(1, followUps.finalized.size)
    }

    @Test
    fun `an operator delete ignores ownership, says whether anything was deleted and only takes a plain reason`() {
        put(PlaybackFixtures.video())

        assertFailsWith<VideoInvalidException> { library.delete("vid1", "has spaces\nand a newline") }
        assertFailsWith<VideoInvalidException> { library.delete("vid1", "") }
        assertEquals(VideoStatus.PLAYABLE, store.rows.getValue("vid1").status)

        assertTrue(library.delete("vid1", "OPERATOR"))
        assertFalse(library.delete("vid1", "OPERATOR"))
        assertFalse(library.delete("nope", "OPERATOR"))
        assertNotNull(followUps.events("vid1").singleOrNull())
    }
}
