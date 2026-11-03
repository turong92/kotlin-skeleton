package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.upload.PublicUploadPurger
import dev.sumin.skeleton.storage.StoragePublicProperties
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.video.PlaybackFixtures.ORIG
import dev.sumin.skeleton.video.PlaybackFixtures.POSTER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 지운 영상의 정리(`video.finalize DELETED`) — 데이터를 지우는 쪽이라 틀리면 남의 영상이 날아간다: 그 영상 폴더와 올린 파일만 지우고(접두사가 닮은 다른 id 도 건드리지 않는다),
 * 공개 영상은 지우기 **전에** CDN 캐시를 비운다(주소는 immutable 이라 지운 뒤에는 목록이 비어 주소를 잃는다). 재시도돼도 같은 결과다.
 */
class VideoDeleteCleanupTest {
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private val events = RecordingEvents()
    private val props = VideoProperties(enabled = true)
    private val web = StorageWebProperties(`public` = StoragePublicProperties(enabled = true, secret = "s".repeat(40), prefix = "public", baseUrl = "https://cdn.example.com"))
    private val purged = mutableListOf<String>()
    private val presentAtPurge = mutableListOf<Boolean>()
    private var purgeFails = false
    private val purger = PublicUploadPurger { urls ->
        if (purgeFails) error("cloudflare down")
        purged += urls
        presentAtPurge += urls.all { url -> storage.objects.containsKey(url.removePrefix("https://cdn.example.com/")) }
    }
    private val finalizer = VideoFinalizer(store, storage, VideoKeys(props, web), events, purger = purger, publicProps = { web.`public` })

    private fun put(v: Video, vararg objects: String) = v.also {
        store.rows[it.id] = it
        objects.forEach { key -> storage.objects[key] = 1 }
    }

    @Test
    fun `a private delete removes the video folder and the uploaded file - not a similar id, not another video, not the purge`() {
        put(
            PlaybackFixtures.video("v1", status = VideoStatus.DELETED).copy(uploadId = "up-1"),
            "videos/p/v1/$ORIG/index.m3u8", "videos/p/v1/$ORIG/s00000.m4s", "videos/p/v1/h-${"a".repeat(32)}/master.m3u8", "videos/p/v1/$POSTER", "videos/u/v1",
        )
        val others = listOf("videos/p/v10/$ORIG/index.m3u8", "videos/p/v1x/$ORIG/index.m3u8", "videos/p/v2/$ORIG/index.m3u8", "videos/u/v2", "videos/u/v10", "public/v/v1/$ORIG/index.m3u8")
        others.forEach { storage.objects[it] = 1 }

        finalizer.handle("v1", VideoFinalizeEvent.DELETED)

        assertEquals(others, storage.objects.keys.toList())
        assertEquals(listOf("up-1"), storage.aborted.map { it.uploadId }, "an unfinished multipart goes too")
        assertTrue(purged.isEmpty(), "a private video has no CDN copy")
        assertEquals(listOf("deleted:v1"), events.calls)
    }

    @Test
    fun `a public delete purges the CDN addresses before the objects are removed, then removes only that folder`() {
        put(
            PlaybackFixtures.video("v1", visibility = VideoVisibility.PUBLIC, status = VideoStatus.DELETED),
            "public/v/v1/$ORIG/index.m3u8", "public/v/v1/$ORIG/s00000.m4s", "public/v/v1/$POSTER",
        )
        val others = listOf("public/v/v10/$ORIG/index.m3u8", "public/v/v2/$POSTER", "public/other.jpg", "videos/p/v1/$ORIG/index.m3u8")
        others.forEach { storage.objects[it] = 1 }

        finalizer.handle("v1", VideoFinalizeEvent.DELETED)

        assertEquals(
            listOf("public/v/v1/$ORIG/index.m3u8", "public/v/v1/$ORIG/s00000.m4s", "public/v/v1/$POSTER").map { "https://cdn.example.com/$it" }.sorted(),
            purged.sorted(),
        )
        assertTrue(presentAtPurge.isNotEmpty() && presentAtPurge.all { it }, "the objects were still there when the purge ran")
        assertEquals(others, storage.objects.keys.toList())
    }

    @Test
    fun `when the purge fails nothing is deleted and the retry finishes the job`() {
        put(PlaybackFixtures.video("v1", visibility = VideoVisibility.PUBLIC, status = VideoStatus.DELETED), "public/v/v1/$ORIG/index.m3u8", "public/v/v1/$POSTER")
        purgeFails = true

        assertFailsWith<IllegalStateException> { finalizer.handle("v1", VideoFinalizeEvent.DELETED) }
        assertEquals(2, storage.objects.size)
        assertTrue(events.calls.isEmpty())

        purgeFails = false
        finalizer.handle("v1", VideoFinalizeEvent.DELETED)
        assertTrue(storage.objects.isEmpty())
        finalizer.handle("v1", VideoFinalizeEvent.DELETED)   // 한 번 더 와도 같은 결과
        assertEquals(listOf("deleted:v1", "deleted:v1"), events.calls)
    }

    @Test
    fun `a video that is not deleted is never cleaned up by this event`() {
        put(PlaybackFixtures.video("v1"), "videos/p/v1/$ORIG/index.m3u8", "videos/u/v1")

        finalizer.handle("v1", VideoFinalizeEvent.DELETED)
        finalizer.handle("unknown", VideoFinalizeEvent.DELETED)

        assertEquals(2, storage.objects.size)
        assertTrue(storage.deleted.isEmpty() && events.calls.isEmpty())
    }
}
