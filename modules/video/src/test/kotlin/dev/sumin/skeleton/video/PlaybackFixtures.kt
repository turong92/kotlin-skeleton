package dev.sumin.skeleton.video

import java.time.Instant

/** 재생 시험이 같이 쓰는 영상 한 줄 — 원본 단 · 호환 단 · 사다리가 모두 준비된 `PLAYABLE` */
object PlaybackFixtures {
    val t0: Instant = Instant.parse("2026-11-04T00:00:00Z")
    const val ORIG = "o-0123456789abcdef"
    const val COMPAT = "c-fedcba9876543210"
    const val POSTER = "p-0123456789abcdef.jpg"
    val BUILD = "a".repeat(32)
    val SECRET = "k".repeat(40)

    fun root(visibility: VideoVisibility) = if (visibility == VideoVisibility.PUBLIC) "public/v" else "videos/p"

    fun video(
        id: String = "vid1",
        visibility: VideoVisibility = VideoVisibility.PRIVATE,
        status: VideoStatus = VideoStatus.PLAYABLE,
        owner: String = "acc_a",
        compat: TrackStatus = TrackStatus.READY,
        ladder: TrackStatus = TrackStatus.READY,
    ): Video {
        val folder = "${root(visibility)}/$id"
        return Video(
            id = id, ownerAccountId = owner, purpose = "note", visibility = visibility, status = status,
            uploadKey = "videos/u/$id", uploadBytes = 1000, lastUploadActivityAt = t0, createdAt = t0, updatedAt = t0,
            srcCodecString = "avc1.640028", srcWidth = 1080, srcHeight = 1920, srcDurationMs = 180_000,
            origKey = "$folder/$ORIG/index.m3u8", origBytes = 330, posterKey = "$folder/$POSTER",
            compatStatus = compat, compatKey = when (compat) { TrackStatus.READY -> "$folder/$COMPAT/index.m3u8"; TrackStatus.SKIPPED -> "$folder/$ORIG/index.m3u8"; else -> null },
            ladderStatus = ladder, ladderBuild = if (ladder == TrackStatus.READY) BUILD else null,
        )
    }
}
