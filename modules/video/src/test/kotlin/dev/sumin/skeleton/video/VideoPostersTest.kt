package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.PublicUploadPurger
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImageStatus
import dev.sumin.skeleton.common.upload.UploadImageVariant
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.StoragePublicProperties
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.video.PlaybackFixtures.POSTER
import dev.sumin.skeleton.video.PlaybackFixtures.SECRET
import dev.sumin.skeleton.video.PlaybackFixtures.t0
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 포스터가 파일 묶음(이미지 모듈)의 검사를 거치는 길과, 올린 사람이 바꿔 끼우는 길. 남의 영상 · 남의 이미지 · 거절된 이미지는 포스터가 될 수 없다 */
class VideoPostersTest {
    private val time = TimeProvider { t0 }
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private val followUps = FakeFollowUps()
    private val images = FakeImages()
    private val props = VideoProperties(enabled = true)
    private val web = StorageWebProperties(`public` = StoragePublicProperties(enabled = true, secret = "s".repeat(40), prefix = "public", baseUrl = "https://cdn.example.com"))
    private val keys = VideoKeys(props, web)
    private val posters = VideoPosters(store, storage, keys, OwnerVideoAccessPolicy, { acc, key -> key.startsWith("uploads/$acc/") }, { images }, followUps, FakeTransactions(), time)
    private val library = VideoLibrary(
        store, OwnerVideoAccessPolicy, StorageVideoPlayback(props, keys, storage, { web.`public` }, VideoPlaybackTokens(SECRET, time)), followUps, FakeTransactions(), time, posters,
    )

    private val raw = "videos/p/vid1/$POSTER"
    private val mine = "uploads/acc_a/u1/file.jpg"

    private fun put(v: Video = PlaybackFixtures.video()) = v.also { store.rows[it.id] = it }

    @Test
    fun `only the owner of the video can replace its poster`() {
        put()
        images.statuses[mine] = ready("a")

        assertFailsWith<VideoNotFoundException> { posters.replace("acc_b", "vid1", mine) }
        assertFailsWith<VideoNotFoundException> { posters.replace("acc_a", "nope", mine) }

        assertEquals(raw, store.rows.getValue("vid1").posterKey)
        assertTrue(storage.copies.isEmpty())
    }

    @Test
    fun `someone else's image cannot become my video's poster, and the image check is not even started for it`() {
        put()
        val theirs = "uploads/acc_b/u9/file.jpg"
        images.statuses[theirs] = ready("b")

        assertFailsWith<VideoNotFoundException> { posters.replace("acc_a", "vid1", theirs) }

        assertEquals(raw, store.rows.getValue("vid1").posterKey)
        assertTrue(storage.copies.isEmpty() && images.ensured.isEmpty())
    }

    @Test
    fun `an image the check refused never becomes the poster, and one still being checked is asked again later`() {
        put()
        listOf(UploadImageState.REJECTED, UploadImageState.UNSUPPORTED, UploadImageState.FAILED).forEach { state ->
            images.statuses[mine] = UploadImageStatus(state)
            assertFailsWith<VideoPosterRejectedException>(state.name) { posters.replace("acc_a", "vid1", mine) }
        }
        images.statuses.remove(mine)   // 아직 등록도 안 됐다
        assertFailsWith<VideoPosterPendingException> { posters.replace("acc_a", "vid1", mine) }
        assertEquals(listOf(mine), images.ensured)
        assertFailsWith<VideoPosterPendingException> { posters.replace("acc_a", "vid1", mine) }   // 이제 PENDING

        assertEquals(raw, store.rows.getValue("vid1").posterKey)
        assertTrue(storage.copies.isEmpty())
    }

    @Test
    fun `a checked image becomes the poster - its display copy is placed in the video folder, the old one is swept and the grant points at the new one`() {
        put()
        images.statuses[mine] = ready("a")

        posters.replace("acc_a", "vid1", mine)

        val row = store.rows.getValue("vid1")
        val copy = storage.copies.single()
        assertEquals("images/a/display.jpg", copy.sourceKey.value)   // 원본 업로드가 아니라 검사 · 메타데이터 제거를 거친 표시본
        assertTrue(Regex("^videos/p/vid1/poster-[0-9a-f]{16}\\.jpg$").matches(copy.destinationKey.value), copy.destinationKey.value)
        assertEquals("private, max-age=31536000, immutable", copy.cacheControl)
        assertEquals(listOf(copy.destinationKey.value, 1280, 720), listOf(row.posterKey, row.posterWidth, row.posterHeight))
        assertEquals(listOf(VideoFinalizeEvent.SWEEP), followUps.events("vid1"))
        assertEquals("http://s3.test/${copy.destinationKey.value}?sig=1", library.playback("acc_a", "vid1").poster)
    }

    @Test
    fun `the worker's poster is hidden until the image check is done, then replaced by the checked copy and its raw image row is dropped`() {
        put()
        assertNull(library.playback("acc_a", "vid1").poster)   // 검사 전의 그림은 내주지 않는다

        assertFailsWith<VideoPosterPendingException> { posters.adopt("vid1") }
        assertEquals(listOf(raw), images.ensured)
        assertEquals(raw, store.rows.getValue("vid1").posterKey)

        images.statuses[raw] = ready("w")
        posters.adopt("vid1")

        val row = store.rows.getValue("vid1")
        assertTrue(row.posterKey!!.startsWith("videos/p/vid1/poster-"))
        assertEquals(listOf(raw), images.forgotten)
        assertNotNull(library.playback("acc_a", "vid1").poster)
        posters.adopt("vid1")   // 두 번째는 아무 일도 안 한다
        assertEquals(1, storage.copies.size)
    }

    @Test
    fun `a worker poster the check refused leaves the video without a poster`() {
        put()
        images.statuses[raw] = UploadImageStatus(UploadImageState.REJECTED)

        posters.adopt("vid1")

        assertNull(store.rows.getValue("vid1").posterKey)
        assertEquals(listOf(raw), images.forgotten)
        assertEquals(listOf(VideoFinalizeEvent.SWEEP), followUps.events("vid1"))
        assertTrue(storage.copies.isEmpty())
    }

    @Test
    fun `sweeping a public video clears the CDN cache of the replaced poster before deleting it`() {
        put(PlaybackFixtures.video(visibility = VideoVisibility.PUBLIC))
        val stale = "public/v/vid1/$POSTER"
        val current = "public/v/vid1/poster-0123456789abcdef.jpg"
        store.rows["vid1"] = store.rows.getValue("vid1").copy(posterKey = current)
        storage.objects[stale] = 5
        storage.objects[current] = 5
        val purged = mutableListOf<String>()
        val deletedWhenPurged = mutableListOf<Int>()
        val purger = PublicUploadPurger { urls -> purged += urls; deletedWhenPurged += storage.deleted.size }
        val finalizer = VideoFinalizer(store, storage, keys, RecordingEvents(), time, purger, { web.`public` })

        finalizer.handle("vid1", VideoFinalizeEvent.SWEEP)

        assertEquals(listOf("https://cdn.example.com/$stale"), purged)
        assertEquals(listOf(0), deletedWhenPurged)
        assertEquals(listOf(stale), storage.deleted)
    }

    @Test
    fun `without the image module the worker's poster is used as it is`() {
        val plain = VideoPosters(store, storage, keys, OwnerVideoAccessPolicy, { _, _ -> true }, { null }, followUps, FakeTransactions(), time)
        put()

        assertEquals(false, plain.enabled)
        assertFailsWith<VideoPosterRejectedException> { plain.replace("acc_a", "vid1", mine) }
        assertEquals(false, plain.hides(store.rows.getValue("vid1")))
        plain.adopt("vid1")
        assertEquals(raw, store.rows.getValue("vid1").posterKey)
    }

    private fun ready(tag: String) = UploadImageStatus(
        UploadImageState.READY, 4000, 2250,
        mapOf(
            UploadImages.DISPLAY to UploadImageVariant("images/$tag/display.jpg", 1280, 720, "image/jpeg"),
            UploadImages.THUMB to UploadImageVariant("images/$tag/thumb.jpg", 400, 225, "image/jpeg"),
        ),
    )

    private class FakeImages : UploadImages {
        val statuses = mutableMapOf<String, UploadImageStatus>()
        val ensured = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        override val variantPrefix = "images"
        override fun status(privateKey: String) = statuses[privateKey]
        override fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration): String? = null
        override fun ensure(privateKey: String) {
            ensured += privateKey
            statuses.putIfAbsent(privateKey, UploadImageStatus(UploadImageState.PENDING))
        }
        override fun forget(privateKeys: Collection<String>) { forgotten += privateKeys }
    }
}
