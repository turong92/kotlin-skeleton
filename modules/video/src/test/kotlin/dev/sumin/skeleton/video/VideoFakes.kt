package dev.sumin.skeleton.video

import dev.sumin.skeleton.storage.AbortMultipartUploadRequest
import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.CompletedMultipartUpload
import dev.sumin.skeleton.storage.CopyObjectRequest
import dev.sumin.skeleton.storage.ListObjectsRequest
import dev.sumin.skeleton.storage.ListUploadedPartsRequest
import dev.sumin.skeleton.storage.ListedObjects
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedMultipartUploadPart
import dev.sumin.skeleton.storage.PresignedMultipartUploadPartRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.PresignedUploadRequest
import dev.sumin.skeleton.storage.PresignedUrl
import dev.sumin.skeleton.storage.StartMultipartUploadRequest
import dev.sumin.skeleton.storage.StartedMultipartUpload
import dev.sumin.skeleton.storage.StoredObjectMetadata
import dev.sumin.skeleton.storage.StoredObjectSummary
import dev.sumin.skeleton.storage.StoredObjectWriteResult
import dev.sumin.skeleton.storage.UploadObjectRequest
import dev.sumin.skeleton.storage.UploadedPart
import java.net.URI
import java.time.Instant

/** 저장소 호출을 기록하는 가짜 — 올린 객체는 [objects](키 → 바이트 수) 에 둔다 */
class FakeStorage : PresignedStorage {
    val started = mutableListOf<StartMultipartUploadRequest>()
    val aborted = mutableListOf<AbortMultipartUploadRequest>()
    val completed = mutableListOf<CompleteMultipartUploadRequest>()
    val deleted = mutableListOf<String>()
    val listed = mutableListOf<ListUploadedPartsRequest>()
    val objects = linkedMapOf<String, Long>()
    var parts: List<UploadedPart> = emptyList()
    var failComplete = false
    private val expires = Instant.parse("2030-01-01T00:00:00Z")

    override fun startMultipartUpload(request: StartMultipartUploadRequest): StartedMultipartUpload {
        started += request
        return StartedMultipartUpload(request.key, "up-${started.size}")
    }

    override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) =
        PresignedMultipartUploadPart(request.key, request.uploadId, request.partNumber, "PUT", URI("http://s3.test/${request.key.value}?part=${request.partNumber}"), emptyMap(), expires)

    override fun completeMultipartUpload(request: CompleteMultipartUploadRequest): CompletedMultipartUpload {
        if (failComplete) error("NoSuchUpload")
        completed += request
        return CompletedMultipartUpload(request.key, request.uploadId, "etag-all")
    }

    override fun abortMultipartUpload(request: AbortMultipartUploadRequest) {
        aborted += request
    }

    override fun listParts(request: ListUploadedPartsRequest): List<UploadedPart> {
        listed += request
        return parts
    }

    override fun metadata(key: ObjectKey): StoredObjectMetadata? = objects[key.value]?.let { StoredObjectMetadata(key, sizeBytes = it) }

    override fun delete(key: ObjectKey) {
        deleted += key.value
        objects.remove(key.value)
    }

    override fun upload(request: UploadObjectRequest) = StoredObjectWriteResult(request.key)
    override fun copy(request: CopyObjectRequest) = StoredObjectWriteResult(request.destinationKey)
    /** [read] 가 돌려줄 객체 내용 — 재생 목록 시험이 채운다 */
    val texts = linkedMapOf<String, String>()
    val reads = mutableListOf<String>()

    override fun read(key: ObjectKey, range: LongRange?): java.io.InputStream {
        reads += key.value
        return (texts[key.value] ?: error("NoSuchKey ${key.value}")).byteInputStream()
    }

    override fun list(request: ListObjectsRequest) =
        ListedObjects(objects.filterKeys { it.startsWith(request.prefix) }.map { (k, v) -> StoredObjectSummary(ObjectKey(k), sizeBytes = v) })

    val signedUploads = mutableListOf<PresignedUploadRequest>()
    val signedDownloads = mutableListOf<PresignedDownloadRequest>()

    override fun presignUpload(request: PresignedUploadRequest): PresignedUrl {
        signedUploads += request
        return PresignedUrl(request.key, "PUT", URI("http://s3.test/${request.key.value}"), request.cacheControl?.let { mapOf("Cache-Control" to it) } ?: emptyMap(), expires)
    }

    override fun presignDownload(request: PresignedDownloadRequest): PresignedUrl {
        signedDownloads += request
        return PresignedUrl(request.key, "GET", URI("http://s3.test/${request.key.value}?sig=1"), emptyMap(), expires)
    }
}

/** 저장소 포트의 메모리 구현 — 전이 규칙은 진짜 구현(video-jdbc)과 같은 조건부 갱신이다 */
class FakeVideoStore : VideoStore {
    val rows = linkedMapOf<String, Video>()
    var failInsert = false

    override fun insert(video: NewVideo): Video {
        if (failInsert) error("db down")
        return Video(
            id = video.id, ownerAccountId = video.ownerAccountId, purpose = video.purpose, visibility = video.visibility, status = VideoStatus.UPLOADING,
            uploadKey = video.uploadKey, uploadId = video.uploadId, uploadBytes = video.uploadBytes, lastUploadActivityAt = video.at, createdAt = video.at, updatedAt = video.at,
        ).also { rows[it.id] = it }
    }

    override fun find(id: String) = rows[id]

    override fun listByOwner(ownerAccountId: String, purpose: String?) =
        rows.values.filter { it.ownerAccountId == ownerAccountId && it.status != VideoStatus.DELETED && (purpose == null || it.purpose == purpose) }

    override fun countInFlight(ownerAccountId: String) =
        rows.values.count { it.ownerAccountId == ownerAccountId && it.status in IN_FLIGHT }

    override fun touchUpload(id: String, at: Instant) {
        rows.computeIfPresent(id) { _, v -> v.copy(lastUploadActivityAt = at) }
    }

    override fun advance(id: String, from: VideoStatus, to: VideoStatus, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status == from } ?: return false
        rows[id] = v.copy(status = to, updatedAt = at, uploadId = if (to == VideoStatus.QUEUED) null else v.uploadId)
        return true
    }

    override fun markDeleted(id: String, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status != VideoStatus.DELETED } ?: return false
        rows[id] = v.copy(status = VideoStatus.DELETED, leaseOwner = null, leaseUntil = null, lane = null, progress = null, updatedAt = at)
        return true
    }

    override fun markUploadAbandoned(id: String, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status == VideoStatus.UPLOADING || it.status == VideoStatus.UPLOADED } ?: return false
        rows[id] = v.copy(status = VideoStatus.FAILED, failCode = "UPLOAD_ABANDONED", updatedAt = at)
        return true
    }

    override fun staleUploads(before: Instant, limit: Int) =
        rows.values.filter { (it.status == VideoStatus.UPLOADING || it.status == VideoStatus.UPLOADED) && it.lastUploadActivityAt < before }.take(limit)

    /** 일꾼 쪽 조건부 갱신 — 진짜 구현(video-jdbc)과 같은 조건이다. 시험은 줄을 [rows] 에 바로 심고 읽는다 */
    override fun claimNext(lane: VideoLane, owner: String, leaseUntil: Instant, ladderBuild: String, at: Instant): Video? {
        val row = rows.values.filter { candidate(lane, it) }.minByOrNull { it.createdAt } ?: return null
        val leased = row.copy(leaseOwner = owner, leaseUntil = leaseUntil, lane = lane, attempts = row.attempts + 1, progress = null, updatedAt = at)
        return when (lane) {
            VideoLane.REMUX -> leased.copy(status = VideoStatus.REMUXING)
            VideoLane.COMPAT -> leased.copy(compatStatus = TrackStatus.RUNNING)
            VideoLane.LADDER -> leased.copy(ladderStatus = TrackStatus.RUNNING, ladderBuild = ladderBuild)
            VideoLane.ANIMATED -> error("not a video lane")
        }.also { rows[it.id] = it }
    }

    private fun candidate(lane: VideoLane, v: Video) = when (lane) {
        VideoLane.REMUX -> v.status == VideoStatus.QUEUED
        VideoLane.COMPAT -> v.status == VideoStatus.PLAYABLE && v.compatStatus == TrackStatus.QUEUED && v.leaseOwner == null
        VideoLane.LADDER -> v.status == VideoStatus.PLAYABLE && v.ladderStatus == TrackStatus.QUEUED && v.leaseOwner == null
        VideoLane.ANIMATED -> false
    }

    override fun extendLease(id: String, owner: String, until: Instant, progress: Int?, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.leaseOwner == owner && it.leaseUntil != null } ?: return false
        rows[id] = v.copy(leaseUntil = until, progress = progress ?: v.progress, updatedAt = at)
        return true
    }

    override fun markPlayable(id: String, owner: String, result: PlayableResult, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status == VideoStatus.REMUXING && it.leaseOwner == owner } ?: return false
        rows[id] = v.copy(
            status = VideoStatus.PLAYABLE, origKey = result.origKey, origBytes = result.origBytes, origSha256 = result.origSha256,
            srcCodecString = result.srcCodecString, srcWidth = result.srcWidth, srcHeight = result.srcHeight, srcDurationMs = result.srcDurationMs,
            posterKey = result.posterKey, compatStatus = result.compat, compatKey = if (result.compat == TrackStatus.SKIPPED) result.origKey else null,
            ladderStatus = result.ladder, attempts = 0, leaseOwner = null, leaseUntil = null, lane = null, progress = null, updatedAt = at,
        )
        return true
    }

    override fun markCompatReady(id: String, owner: String, key: String, bytes: Long, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status == VideoStatus.PLAYABLE && it.compatStatus == TrackStatus.RUNNING && it.leaseOwner == owner } ?: return false
        rows[id] = v.copy(compatStatus = TrackStatus.READY, compatKey = key, compatBytes = bytes, attempts = 0, leaseOwner = null, leaseUntil = null, lane = null, progress = null, updatedAt = at)
        return true
    }

    override fun markLadderReady(id: String, owner: String, build: String, bytes: Long, rungsJson: String, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status == VideoStatus.PLAYABLE && it.ladderStatus == TrackStatus.RUNNING && it.leaseOwner == owner } ?: return false
        rows[id] = v.copy(ladderStatus = TrackStatus.READY, ladderBuild = build, ladderBytes = bytes, ladderRungs = rungsJson, attempts = 0, leaseOwner = null, leaseUntil = null, lane = null, progress = null, updatedAt = at)
        return true
    }

    override fun failLeased(id: String, owner: String, code: String, detail: String?, retry: Boolean, at: Instant): LeaseEnd? {
        val v = rows[id]?.takeIf { it.leaseOwner == owner } ?: return null
        val requeue = retry && v.attempts < VideoStatus.MAX_ATTEMPTS
        val released = v.copy(leaseOwner = null, leaseUntil = null, lane = null, progress = null, updatedAt = at)
        rows[id] = when (v.lane) {
            VideoLane.REMUX -> if (requeue) released.copy(status = VideoStatus.QUEUED) else released.copy(status = VideoStatus.FAILED, failCode = code, failDetail = detail)
            VideoLane.COMPAT -> if (requeue) released.copy(compatStatus = TrackStatus.QUEUED) else released.copy(compatStatus = TrackStatus.FAILED, failCode = code, failDetail = detail, attempts = 0)
            VideoLane.LADDER -> if (requeue) released.copy(ladderStatus = TrackStatus.QUEUED) else released.copy(ladderStatus = TrackStatus.FAILED, failCode = code, failDetail = detail, attempts = 0)
            VideoLane.ANIMATED, null -> return null
        }
        return if (requeue) LeaseEnd.REQUEUED else LeaseEnd.FAILED
    }

    override fun expireLeases(now: Instant, at: Instant): LeaseExpiry {
        var requeued = 0
        val failed = mutableListOf<ExpiredFailure>()
        for (v in rows.values.toList()) {
            val expired = v.leaseOwner != null && v.leaseUntil != null && v.leaseUntil < now
            if (!expired) continue
            val exhausted = v.attempts >= VideoStatus.MAX_ATTEMPTS
            val released = v.copy(leaseOwner = null, leaseUntil = null, lane = null, progress = null, updatedAt = at)
            when {
                v.status == VideoStatus.REMUXING ->
                    if (exhausted) { rows[v.id] = released.copy(status = VideoStatus.FAILED, failCode = VideoFailCode.LEASE_EXPIRED); failed += ExpiredFailure(v.id, true) }
                    else { rows[v.id] = released.copy(status = VideoStatus.QUEUED); requeued++ }
                v.status == VideoStatus.PLAYABLE && v.lane == VideoLane.COMPAT ->
                    if (exhausted) { rows[v.id] = released.copy(compatStatus = TrackStatus.FAILED, failCode = VideoFailCode.LEASE_EXPIRED, attempts = 0); failed += ExpiredFailure(v.id, false) }
                    else { rows[v.id] = released.copy(compatStatus = TrackStatus.QUEUED); requeued++ }
                v.status == VideoStatus.PLAYABLE && v.lane == VideoLane.LADDER ->
                    if (exhausted) { rows[v.id] = released.copy(ladderStatus = TrackStatus.FAILED, failCode = VideoFailCode.LEASE_EXPIRED, attempts = 0); failed += ExpiredFailure(v.id, false) }
                    else { rows[v.id] = released.copy(ladderStatus = TrackStatus.QUEUED); requeued++ }
            }
        }
        return LeaseExpiry(requeued, failed)
    }

    override fun clearUploadKey(id: String, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status == VideoStatus.PLAYABLE && it.uploadKey != null } ?: return false
        rows[id] = v.copy(uploadKey = null, updatedAt = at)
        return true
    }

    override fun queueDeferredLadders(floor: Int, at: Instant): Int {
        val ids = rows.values.filter { it.status == VideoStatus.PLAYABLE && it.ladderStatus == TrackStatus.NONE && minOf(it.srcWidth ?: 0, it.srcHeight ?: 0) > floor }.map { it.id }
        ids.forEach { rows[it] = rows.getValue(it).copy(ladderStatus = TrackStatus.QUEUED, updatedAt = at) }
        return ids.size
    }

    override fun countQueuedRemux() = rows.values.count { it.status == VideoStatus.QUEUED }

    override fun countClaimableTracks() = rows.values.count { candidate(VideoLane.COMPAT, it) || candidate(VideoLane.LADDER, it) }

    override fun countCompatQueuedBefore(before: Instant) =
        rows.values.count { it.status == VideoStatus.PLAYABLE && it.compatStatus == TrackStatus.QUEUED && it.updatedAt < before }

    override fun countFailedSince(since: Instant) =
        rows.values.count { it.updatedAt >= since && it.status != VideoStatus.DELETED && (it.status == VideoStatus.FAILED || it.compatStatus == TrackStatus.FAILED || it.ladderStatus == TrackStatus.FAILED) }

    private companion object {
        val IN_FLIGHT = setOf(VideoStatus.UPLOADING, VideoStatus.UPLOADED, VideoStatus.QUEUED, VideoStatus.REMUXING)
    }
}

class FakeVideoWorkerStore : VideoWorkerStore {
    val rows = linkedMapOf<Long, VideoWorker>()
    private val hashes = linkedMapOf<Long, String>()

    override fun insert(name: String, lanes: Set<VideoLane>, tokenSha256: String, at: Instant): VideoWorker {
        val id = rows.size + 1L
        return VideoWorker(id, name, lanes, at, null, null).also { rows[id] = it; hashes[id] = tokenSha256 }
    }

    override fun findActiveByTokenSha256(tokenSha256: String) = hashes.entries.firstOrNull { it.value == tokenSha256 }?.let { rows[it.key] }?.takeIf { it.revokedAt == null }

    override fun existsByTokenSha256(tokenSha256: String) = tokenSha256 in hashes.values

    override fun touch(id: Long, at: Instant) {
        rows.computeIfPresent(id) { _, w -> w.copy(lastSeenAt = at) }
    }

    override fun revoke(id: Long, at: Instant): Boolean {
        val w = rows[id]?.takeIf { it.revokedAt == null } ?: return false
        rows[id] = w.copy(revokedAt = at)
        return true
    }

    override fun list() = rows.values.toList()

    override fun lastSeenRemux() = rows.values.filter { it.revokedAt == null && VideoLane.REMUX in it.lanes }.mapNotNull { it.lastSeenAt }.maxOrNull()
}

/** 후속 작업 포트의 기록 — 진짜 구현은 한 트랜잭션 안에서 작업 큐에 넣는다 */
class FakeFollowUps : VideoFollowUps {
    val finalized = mutableListOf<Triple<String, VideoFinalizeEvent, java.time.Duration>>()
    var wakes = 0

    override fun finalize(videoId: String, event: VideoFinalizeEvent, delay: java.time.Duration) {
        finalized += Triple(videoId, event, delay)
    }

    override fun wake() {
        wakes++
    }

    fun events(videoId: String) = finalized.filter { it.first == videoId }.map { it.second }
}

class FakeTransactions : VideoTransactions {
    var runs = 0

    override fun <T> run(block: () -> T): T {
        runs++
        return block()
    }
}

class RecordingEvents : VideoEvents {
    val calls = mutableListOf<String>()

    override fun onPlayable(video: Video) { calls += "playable:${video.id}" }
    override fun onTrackReady(video: Video, lane: VideoLane) { calls += "track:${lane.name}:${video.id}" }
    override fun onFailed(video: Video) { calls += "failed:${video.id}" }
    override fun onDeleted(video: Video) { calls += "deleted:${video.id}" }
}

/** 변환 일 표의 메모리 구현 - 전이 규칙은 진짜 구현(video-jdbc)과 같은 조건부 갱신이다 */
class FakeDisplayJobStore : DisplayJobStore {
    val rows = linkedMapOf<String, DisplayJob>()

    override fun enqueue(keyHash: String, objectKey: String, at: Instant) {
        rows.putIfAbsent(keyHash, DisplayJob(keyHash, objectKey, DisplayState.QUEUED, 0, null, null))
    }

    override fun find(keyHash: String) = rows[keyHash]

    override fun claimNext(owner: String, leaseUntil: Instant, at: Instant): DisplayJob? {
        val job = rows.values.firstOrNull { it.state == DisplayState.QUEUED } ?: return null
        return job.copy(state = DisplayState.RUNNING, attempts = job.attempts + 1, leaseOwner = owner, leaseUntil = leaseUntil).also { rows[it.keyHash] = it }
    }

    override fun extendLease(keyHash: String, owner: String, until: Instant, at: Instant): Boolean {
        val job = rows[keyHash]?.takeIf { it.state == DisplayState.RUNNING && it.leaseOwner == owner } ?: return false
        rows[keyHash] = job.copy(leaseUntil = until)
        return true
    }

    override fun finish(keyHash: String, owner: String, at: Instant): Boolean {
        val job = rows[keyHash]?.takeIf { it.state == DisplayState.RUNNING && it.leaseOwner == owner } ?: return false
        rows[keyHash] = job.copy(state = DisplayState.DONE, leaseOwner = null, leaseUntil = null)
        return true
    }

    override fun release(keyHash: String, owner: String?, retry: Boolean, code: String, at: Instant): Boolean {
        val job = rows[keyHash]?.takeIf { it.state == DisplayState.RUNNING && (owner == null || it.leaseOwner == owner) } ?: return false
        val again = retry && job.attempts < VideoStatus.MAX_ATTEMPTS
        rows[keyHash] = job.copy(state = if (again) DisplayState.QUEUED else DisplayState.FAILED, leaseOwner = null, leaseUntil = null)
        return true
    }

    override fun expireLeases(now: Instant): Int {
        val expired = rows.values.filter { it.state == DisplayState.RUNNING && it.leaseUntil != null && it.leaseUntil < now }
        expired.forEach { release(it.keyHash, null, true, "LEASE_EXPIRED", now) }
        return expired.size
    }
}

/** storage-image 쪽 면의 가짜 - [copyExt] 에 있는 이미지만 「움직이는 READY」다. [used] 는 표시본으로 바꾼 기록 */
class FakeDisplayTarget(private val folder: String) : DisplayTarget {
    val copyExt = linkedMapOf<String, String>()
    val used = mutableListOf<Pair<String, String>>()

    override fun folder(keyHash: String) = folder
    override fun copyExtension(keyHash: String) = copyExt[keyHash]
    override fun use(keyHash: String, ext: String): Boolean {
        if (keyHash !in copyExt || ext == copyExt[keyHash]) return false
        used += keyHash to ext
        return true
    }
}
