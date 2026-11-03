package dev.sumin.skeleton.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 주 상태 전이표 — 표에 없는 전이는 null (저장소의 조건부 갱신이 DB 에서 같은 표를 지킨다) */
class VideoStatusTest {
    @Test
    fun `the happy path walks UPLOADING to PLAYABLE one step at a time`() {
        assertEquals(VideoStatus.UPLOADED, VideoStatus.UPLOADING.next(VideoEvent.COMPLETE))
        assertEquals(VideoStatus.QUEUED, VideoStatus.UPLOADED.next(VideoEvent.VERIFIED))
        assertEquals(VideoStatus.REMUXING, VideoStatus.QUEUED.next(VideoEvent.CLAIM))
        assertEquals(VideoStatus.PLAYABLE, VideoStatus.REMUXING.next(VideoEvent.REMUX_DONE))
    }

    @Test
    fun `an event out of order is not in the table`() {
        assertNull(VideoStatus.UPLOADING.next(VideoEvent.VERIFIED))
        assertNull(VideoStatus.QUEUED.next(VideoEvent.REMUX_DONE))
        assertNull(VideoStatus.PLAYABLE.next(VideoEvent.CLAIM))
        assertNull(VideoStatus.FAILED.next(VideoEvent.COMPLETE))
    }

    @Test
    fun `a lost lease queues the work again until the third attempt, then fails it`() {
        assertEquals(VideoStatus.QUEUED, VideoStatus.REMUXING.next(VideoEvent.LEASE_EXPIRED, attempts = 1))
        assertEquals(VideoStatus.QUEUED, VideoStatus.REMUXING.next(VideoEvent.LEASE_EXPIRED, attempts = 2))
        assertEquals(VideoStatus.FAILED, VideoStatus.REMUXING.next(VideoEvent.LEASE_EXPIRED, attempts = VideoStatus.MAX_ATTEMPTS))
        assertNull(VideoStatus.PLAYABLE.next(VideoEvent.LEASE_EXPIRED))
    }

    @Test
    fun `delete is possible from every state except DELETED itself`() {
        VideoStatus.entries.filter { it != VideoStatus.DELETED }.forEach { assertEquals(VideoStatus.DELETED, it.next(VideoEvent.DELETE), "$it") }
        assertNull(VideoStatus.DELETED.next(VideoEvent.DELETE))
    }
}
