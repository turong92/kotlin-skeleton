package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.video.PlaybackFixtures.BUILD
import dev.sumin.skeleton.video.PlaybackFixtures.COMPAT
import dev.sumin.skeleton.video.PlaybackFixtures.ORIG
import dev.sumin.skeleton.video.PlaybackFixtures.SECRET
import dev.sumin.skeleton.video.PlaybackFixtures.t0
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 비공개 재생 목록을 내줄 때 저장된 목록의 조각 줄을 출입증 남은 수명의 presign 으로 바꿔 쓴다. 틀리면 보안 사고다 — 위조 · 만료 출입증에는 presign 이 하나도 안 만들어지고,
 * 폴더 밖 줄(다른 영상 · 절대 주소 · 다른 폴더)은 서명되지 않는다.
 */
class VideoPlaylistsTest {
    private var now: Instant = t0
    private val storage = FakeStorage()
    private val store = FakeVideoStore()
    private val props = VideoProperties(enabled = true)
    private val tokens = VideoPlaybackTokens(SECRET, TimeProvider { now })
    private val playlists = VideoPlaylists(tokens, store, VideoKeys(props, StorageWebProperties()), storage)
    private val video = PlaybackFixtures.video().also { store.rows[it.id] = it }
    private val folder = "videos/p/vid1/"

    private fun token(ttl: Duration = Duration.ofHours(4), id: String = "vid1") = tokens.issue(id, ttl).value

    private val origPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:4
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:4.0,
        s00000.m4s
        #EXTINF:4.0,
        s00001.m4s
        #EXT-X-ENDLIST
    """.trimIndent()

    private fun signedKeys() = storage.signedDownloads.map { it.key.value }

    @Test
    fun `init and segment lines become presigned addresses for the lifetime the token has left`() {
        storage.texts["$folder$ORIG/index.m3u8"] = origPlaylist
        val tok = token(Duration.ofHours(4))
        now = t0.plus(Duration.ofHours(1))

        val out = playlists.playlist(tok, "$ORIG/index.m3u8")

        val lines = out.lines()
        assertEquals("#EXT-X-MAP:URI=\"http://s3.test/$folder$ORIG/init.mp4?sig=1\"", lines[3])
        assertEquals("http://s3.test/$folder$ORIG/s00000.m4s?sig=1", lines[5])
        assertEquals("http://s3.test/$folder$ORIG/s00001.m4s?sig=1", lines[7])
        assertEquals(listOf("#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-TARGETDURATION:4"), lines.take(3))
        assertEquals(listOf("$folder$ORIG/init.mp4", "$folder$ORIG/s00000.m4s", "$folder$ORIG/s00001.m4s"), signedKeys())
        assertTrue(storage.signedDownloads.all { it.expiresIn == Duration.ofHours(3) }, storage.signedDownloads.map { it.expiresIn }.toString())
    }

    @Test
    fun `lines pointing outside this folder are not signed - another video, absolute addresses, another folder, a single big mp4, a query`() {
        storage.texts["$folder$ORIG/index.m3u8"] = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            ../other_video/$ORIG/s00000.m4s
            ../../videos/p/other_video/$ORIG/s00000.m4s
            https://evil.example.com/s00000.m4s
            //evil.example.com/s00000.m4s
            /videos/p/other_video/s00000.m4s
            $COMPAT/s00000.m4s
            whole.mp4
            s00000.m4s?x=1
            s0.m4s
            s00001.m4s
        """.trimIndent()

        val out = playlists.playlist(token(), "$ORIG/index.m3u8")

        assertEquals(listOf("$folder$ORIG/init.mp4", "$folder$ORIG/s00001.m4s"), signedKeys())
        val kept = out.lines()
        listOf(
            "../other_video/$ORIG/s00000.m4s", "../../videos/p/other_video/$ORIG/s00000.m4s", "https://evil.example.com/s00000.m4s", "//evil.example.com/s00000.m4s",
            "/videos/p/other_video/s00000.m4s", "$COMPAT/s00000.m4s", "whole.mp4", "s00000.m4s?x=1", "s0.m4s",
        ).forEach { assertTrue(it in kept, "unsigned line stays as written: $it") }
        assertTrue(storage.signedDownloads.none { "other_video" in it.key.value })
    }

    @Test
    fun `a master playlist is served as is - its relative playlist lines resolve under the token path and nothing is presigned`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=720x1280
            v0.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=17000000
            ../$ORIG/index.m3u8
        """.trimIndent()
        storage.texts["${folder}h-$BUILD/master.m3u8"] = master

        assertEquals(master, playlists.playlist(token(), "h-$BUILD/master.m3u8"))
        assertTrue(storage.signedDownloads.isEmpty())
    }

    @Test
    fun `a ladder variant playlist signs its init and segments inside its own build folder`() {
        storage.texts["${folder}h-$BUILD/v0.m3u8"] = "#EXTM3U\n#EXT-X-MAP:URI=\"init_0.mp4\"\n#EXTINF:4.0,\nv0_001.m4s\n#EXTINF:4.0,\n../$ORIG/s00000.m4s\n"

        playlists.playlist(token(), "h-$BUILD/v0.m3u8")

        assertEquals(listOf("${folder}h-$BUILD/init_0.mp4", "${folder}h-$BUILD/v0_001.m4s"), signedKeys())
    }

    @Test
    fun `a compat playlist is served from the compat folder`() {
        storage.texts["$folder$COMPAT/index.m3u8"] = origPlaylist

        playlists.playlist(token(), "$COMPAT/index.m3u8")

        assertEquals(listOf("$folder$COMPAT/init.mp4", "$folder$COMPAT/s00000.m4s", "$folder$COMPAT/s00001.m4s"), signedKeys())
    }

    @Test
    fun `a forged, expired or foreign token answers not found and presigns and reads nothing`() {
        storage.texts["$folder$ORIG/index.m3u8"] = origPlaylist
        val good = token()
        val (v, id, exp, mac) = good.split('.')
        val expired = token(Duration.ofMinutes(1))
        now = t0.plus(Duration.ofMinutes(2))
        val forged = listOf("$v.$id.$exp.${mac.reversed()}", "$v.other_video.$exp.$mac", "garbage", expired, VideoPlaybackTokens("z".repeat(40), TimeProvider { now }).issue("vid1", Duration.ofHours(1)).value)

        forged.forEach { assertFailsWith<VideoNotFoundException>(it) { playlists.playlist(it, "$ORIG/index.m3u8") } }

        assertTrue(storage.signedDownloads.isEmpty() && storage.reads.isEmpty())
    }

    @Test
    fun `a valid token of an unknown video, a deleted one, a public one or one that is not playable answers not found`() {
        storage.texts["$folder$ORIG/index.m3u8"] = origPlaylist
        store.rows["gone"] = PlaybackFixtures.video("gone", status = VideoStatus.DELETED)
        store.rows["pub"] = PlaybackFixtures.video("pub", visibility = VideoVisibility.PUBLIC)
        store.rows["queued"] = PlaybackFixtures.video("queued", status = VideoStatus.REMUXING)

        listOf("nobody", "gone", "pub", "queued").forEach { id ->
            assertFailsWith<VideoNotFoundException>(id) { playlists.playlist(token(id = id), "$ORIG/index.m3u8") }
        }

        assertTrue(storage.signedDownloads.isEmpty() && storage.reads.isEmpty())
    }

    @Test
    fun `only the playlists this video has are served - not a segment, a folder it does not own, a ladder that is not ready, another build, a path trick`() {
        storage.texts["$folder$ORIG/index.m3u8"] = origPlaylist
        store.rows["noladder"] = PlaybackFixtures.video("noladder", ladder = TrackStatus.NONE)
        val tok = token()

        listOf(
            "$ORIG/s00000.m4s", "$ORIG/init.mp4", "o-ffffffffffffffff/index.m3u8", "c-ffffffffffffffff/index.m3u8", "h-${"b".repeat(32)}/master.m3u8",
            "h-$BUILD/master.mp4", "h-$BUILD/../$ORIG/index.m3u8", "../vid1/$ORIG/index.m3u8", "/$ORIG/index.m3u8", "$ORIG/./index.m3u8", "index.m3u8", "",
        ).forEach { assertFailsWith<VideoNotFoundException>(it) { playlists.playlist(tok, it) } }
        assertFailsWith<VideoNotFoundException> { playlists.playlist(token(id = "noladder"), "h-$BUILD/master.m3u8") }

        assertTrue(storage.signedDownloads.isEmpty() && storage.reads.isEmpty())
    }
}
