package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.ListObjectsRequest
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.PresignedUploadRequest
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat

/**
 * 일꾼이 집는 일 — claim · heartbeat · upload-urls · complete · fail. 집기 · 임대는 영상 줄 자체(`FOR UPDATE SKIP LOCKED`)이고, 일꾼은 저장소 키를 갖지 않는다:
 * 입력은 서버가 서명한 읽기 주소, 결과는 허용 이름으로만 서명한 올리기 주소. 끝내기는 모두 조건부 갱신이라 **먼저 끝낸 쪽이 이기고** 늦은 쪽은 무시된다(멱등).
 * 결과는 저장소 폴더를 나열해 존재 · 합계 크기 · 폴더 이름의 해시로 대조한다. **올린 원 파일은 여기서 지우지 않는다** — 원본 단을 검증한 뒤 [VideoFinalizer] 가 한 시간 뒤에 지운다.
 * 토큰 · 서명 주소는 로그에 남기지 않는다
 */
class VideoJobs(
    private val props: VideoProperties,
    private val store: VideoStore,
    private val storage: PresignedStorage,
    private val keys: VideoKeys,
    private val inputs: VideoWorkerInputs,
    private val followUps: VideoFollowUps,
    private val tx: VideoTransactions,
    private val waker: VideoWaker,
    private val time: TimeProvider,
) {
    private val random = SecureRandom()

    /** 허용 차선(토큰 ∩ 요청) 안에서 다음 일 하나. 없으면 null. 집기 전에 임대가 끝난 일을 거둔다 */
    fun claim(worker: VideoWorker, workerId: String, requested: Set<VideoLane>?): VideoJob? {
        requireWorkerId(workerId)
        val lanes = if (requested.isNullOrEmpty()) worker.lanes else worker.lanes.intersect(requested)
        return tx.run {
            val now = time.now()
            VideoLeases.expire(store, followUps, waker, props, now, now)
            val leaseUntil = now.plus(props.worker.leaseDuration)
            VideoLane.entries.filter { it in lanes }.firstNotNullOfOrNull { lane ->
                store.claimNext(lane, workerId, leaseUntil, newBuild(), now)?.let { job(it, lane) }
            }
        }
    }

    /** 임대를 늘린다(+ 진행률). 소유자가 아니면 409 */
    fun heartbeat(worker: VideoWorker, workerId: String, jobId: String, progress: Int?): Instant {
        val video = leased(worker, workerId, jobId)
        val until = time.now().plus(props.worker.leaseDuration)
        val ok = tx.run { store.extendLease(video.id, workerId, until, progress?.coerceIn(0, 100), time.now()) }
        if (!ok) throw VideoLeaseLostException()
        return until
    }

    /** 허용 이름만 서명한다 — 하나라도 어긋나면 하나도 서명하지 않는다. 해시가 든 이름이라 `immutable` 캐시를 서명에 싣는다(일꾼은 응답의 headers 를 그대로 보낸다) */
    fun uploadUrls(worker: VideoWorker, workerId: String, jobId: String, names: List<String>): List<UploadUrl> {
        val video = leased(worker, workerId, jobId)
        val lane = video.lane ?: throw VideoLeaseLostException()
        if (names.isEmpty() || names.size > MAX_UPLOAD_URLS) throw VideoWorkerInvalidException("names")
        if (names.any { !VideoResultVerifier.allowedName(lane, video.ladderBuild, it) }) throw VideoPathNotAllowedException()
        val folder = keys.folder(video)
        return names.map { name ->
            val signed = storage.presignUpload(
                PresignedUploadRequest(
                    key = ObjectKey("$folder$name"),
                    contentType = VideoResultVerifier.contentTypeOf(name),
                    expiresIn = props.worker.urlTtl,
                    cacheControl = keys.cacheControl(video),
                ),
            )
            UploadUrl(name, signed.method, signed.url.toString(), signed.headers, signed.expiresAt)
        }
    }

    /**
     * 결과를 확인하고 반영한다. 차선은 어떤 칸이 있느냐로 안다. **이미 반영된 일은 아무것도 안 바꾸고 돌아온다** — 같은 `complete` 두 번.
     * 아직 진행 중인 일을 임대 소유자가 아닌 쪽(임대를 잃은 일꾼)이 끝내려 하면 409 로 무시된다
     */
    fun complete(worker: VideoWorker, workerId: String, jobId: String, result: VideoJobResult) {
        requireWorkerId(workerId)
        requireLane(worker, resultLane(result))
        val video = find(jobId)
        when {
            result.orig != null -> completeRemux(workerId, video, result)
            result.compat != null -> completeCompat(workerId, video, result.compat)
            else -> completeLadder(workerId, video, result)
        }
    }

    /** 일꾼이 낸 실패 — [code] 는 표에 있는 것만. [retry] 이고 시도가 남았으면 다시 줄 세운다 */
    fun fail(worker: VideoWorker, workerId: String, jobId: String, code: String, detail: String?, retry: Boolean) {
        requireWorkerId(workerId)
        if (code !in VideoFailCode.WORKER_REPORTABLE) throw VideoFailCodeInvalidException()
        val video = find(jobId)
        video.lane?.let { requireLane(worker, it) }
        if (video.status == VideoStatus.FAILED) return   // 이미 실패로 닫혔다 — 같은 보고가 또 왔다
        if (video.leaseOwner != workerId) throw VideoLeaseLostException()
        tx.run {
            val end = store.failLeased(video.id, workerId, code, detail?.take(MAX_DETAIL), retry, time.now()) ?: throw VideoLeaseLostException()
            val delay = props.cleanupDelay
            when (end) {
                LeaseEnd.FAILED -> if (video.lane == VideoLane.REMUX) {
                    followUps.finalize(video.id, VideoFinalizeEvent.FAILED)
                    followUps.finalize(video.id, VideoFinalizeEvent.SWEEP, delay)
                } else {
                    followUps.finalize(video.id, VideoFinalizeEvent.TRACK_FAILED)
                    followUps.finalize(video.id, VideoFinalizeEvent.SWEEP, delay)
                }
                LeaseEnd.REQUEUED -> if (video.lane != VideoLane.REMUX) requestWake()
            }
        }
    }

    private fun completeRemux(workerId: String, video: Video, result: VideoJobResult) {
        when (video.status) {
            VideoStatus.REMUXING -> if (video.leaseOwner != workerId) throw VideoLeaseLostException()
            VideoStatus.PLAYABLE -> return   // 이미 끝났다
            else -> throw VideoLeaseLostException()
        }
        val orig = result.orig!!
        val probe = result.probe?.takeIf { it.width > 0 && it.height > 0 && it.durationMs > 0 } ?: throw VideoWorkerInvalidException("probe")
        if (!VideoResultVerifier.isOriginalPlaylist(orig.name)) throw VideoPathNotAllowedException()
        val sha = orig.sha256 ?: throw VideoWorkerInvalidException("sha256")
        if (!VideoResultVerifier.hashMatches(orig.name, sha)) throw VideoResultMismatchException("hash")
        val poster = result.poster
        if (poster != null && !VideoResultVerifier.allowedName(VideoLane.REMUX, null, poster.name)) throw VideoPathNotAllowedException()
        val folder = keys.folder(video)
        // 원본 단 = 폴더 — 변형 목록 · init · 조각이 있고 합 크기가 보고와 같아야 한다
        val origSize = requireFolder("$folder${VideoResultVerifier.folderOf(orig.name)}", orig.bytes)
        if (poster != null) requireSize("$folder${poster.name}", poster.bytes)
        val compat = if (result.compatible == true) TrackStatus.SKIPPED else TrackStatus.QUEUED
        val ladder = when {
            minOf(probe.width, probe.height) <= LADDER_FLOOR -> TrackStatus.SKIPPED
            props.ladder -> TrackStatus.QUEUED
            else -> TrackStatus.NONE
        }
        val playable = PlayableResult(
            origKey = "$folder${orig.name}", origBytes = origSize, origSha256 = sha,
            srcContainer = probe.container?.take(64), srcCodec = probe.codec?.substringBefore('.')?.take(32), srcCodecString = probe.codec?.take(64),
            srcWidth = probe.width, srcHeight = probe.height, srcRotation = probe.rotation, srcDurationMs = probe.durationMs, srcFps = probe.fps,
            srcBitrate = probe.bitrate, srcAudioCodec = probe.audioCodec?.take(32),
            posterKey = poster?.let { "$folder${it.name}" }, posterWidth = poster?.width, posterHeight = poster?.height,
            compat = compat, ladder = ladder,
        )
        tx.run {
            if (!store.markPlayable(video.id, workerId, playable, time.now())) return@run   // 동시에 온 다른 호출이 이겼다
            followUps.finalize(video.id, VideoFinalizeEvent.PLAYABLE)
            followUps.finalize(video.id, VideoFinalizeEvent.UPLOAD_CLEANUP, props.cleanupDelay)
            if (compat == TrackStatus.QUEUED || ladder == TrackStatus.QUEUED) requestWake()
        }
    }

    private fun completeCompat(workerId: String, video: Video, file: ResultFile) {
        if (video.status != VideoStatus.PLAYABLE) throw VideoLeaseLostException()
        when (video.compatStatus) {
            TrackStatus.READY -> return
            TrackStatus.RUNNING -> if (video.leaseOwner != workerId) throw VideoLeaseLostException()
            else -> throw VideoLeaseLostException()
        }
        if (!VideoResultVerifier.isCompatPlaylist(file.name)) throw VideoPathNotAllowedException()
        val folder = keys.folder(video)
        val size = requireFolder("$folder${VideoResultVerifier.folderOf(file.name)}", file.bytes)
        tx.run {
            if (store.markCompatReady(video.id, workerId, "$folder${file.name}", size, time.now())) trackDone(video.id, VideoFinalizeEvent.COMPAT_READY)
        }
    }

    private fun completeLadder(workerId: String, video: Video, result: VideoJobResult) {
        if (video.status != VideoStatus.PLAYABLE) throw VideoLeaseLostException()
        when (video.ladderStatus) {
            TrackStatus.READY -> return
            TrackStatus.RUNNING -> if (video.leaseOwner != workerId) throw VideoLeaseLostException()
            else -> throw VideoLeaseLostException()
        }
        val build = result.ladderBuild!!
        if (build != video.ladderBuild || !VideoResultVerifier.isBuild(build)) throw VideoResultMismatchException("build")
        val rungs = result.rungs.orEmpty()
        if (rungs.isEmpty() || rungs.any { !RUNG_NAME.matches(it.name) || it.height <= 0 || it.bandwidth <= 0 }) throw VideoWorkerInvalidException("rungs")
        val folder = "${keys.folder(video)}h-$build/"
        val listing = listFolder(folder)
        if ("${folder}master.m3u8" !in listing.keys || listing.bytes <= 0) throw VideoResultMismatchException("master")
        val json = rungs.joinToString(",", "[", "]") { """{"name":"${it.name}","h":${it.height},"bw":${it.bandwidth}}""" }
        tx.run {
            if (store.markLadderReady(video.id, workerId, build, listing.bytes, json, time.now())) trackDone(video.id, VideoFinalizeEvent.LADDER_READY)
        }
    }

    private fun trackDone(videoId: String, ready: VideoFinalizeEvent) {
        followUps.finalize(videoId, ready)
        followUps.finalize(videoId, VideoFinalizeEvent.SWEEP, props.cleanupDelay)
    }

    private fun job(video: Video, lane: VideoLane): VideoJob {
        val minutes = (video.srcDurationMs ?: 0) / 60_000.0
        val input = if (lane == VideoLane.REMUX) {
            storage.presignDownload(PresignedDownloadRequest(ObjectKey(checkNotNull(video.uploadKey) { "queued video without an uploaded file" }), expiresIn = props.worker.urlTtl)).url.toString()
        } else {
            inputs.originalPlaylistUrl(video, props.worker.urlTtl)
        }
        return VideoJob(
            jobId = video.id,
            lane = lane,
            attempt = video.attempts,
            leaseUntil = video.leaseUntil!!,
            inputUrl = input,
            inputBytes = if (lane == VideoLane.REMUX) video.uploadBytes else video.origBytes ?: 0,
            width = video.srcWidth,
            height = video.srcHeight,
            durationMs = video.srcDurationMs,
            codec = video.srcCodecString,
            expectedOutputBytes = when (lane) {
                VideoLane.REMUX -> video.uploadBytes
                VideoLane.COMPAT -> (minutes * COMPAT_BYTES_PER_MINUTE).toLong()
                VideoLane.LADDER -> (minutes * LADDER_BYTES_PER_MINUTE).toLong()
            },
            build = if (lane == VideoLane.LADDER) video.ladderBuild else null,
            origFolder = if (lane == VideoLane.REMUX) null else video.origKey?.substringBeforeLast('/')?.substringAfterLast('/'),
            verifyDecode = lane == VideoLane.REMUX && video.visibility == VideoVisibility.PUBLIC && props.verifyPublic,
        )
    }

    private fun requestWake() {
        if (waker.enabled) followUps.wake()
    }

    /** 인증된 토큰의 허용 차선 밖 일은 못 한다 — `workerId` 는 본문 문자열이라 신원이 아니다(차선은 집을 때만 아니라 서명 · 끝내기에도 지킨다) */
    private fun requireLane(worker: VideoWorker, lane: VideoLane) {
        if (lane !in worker.lanes) throw VideoWorkerForbiddenException()
    }

    private fun resultLane(result: VideoJobResult): VideoLane = when {
        result.orig != null -> VideoLane.REMUX
        result.compat != null -> VideoLane.COMPAT
        result.ladderBuild != null -> VideoLane.LADDER
        else -> throw VideoWorkerInvalidException("result")
    }

    private fun find(jobId: String): Video = store.find(jobId)?.takeIf { it.status != VideoStatus.DELETED } ?: throw VideoNotFoundException()

    /** 임대 소유자가 [workerId] 인 일만 — 그 차선이 이 토큰의 차선 안일 때 */
    private fun leased(worker: VideoWorker, workerId: String, jobId: String): Video {
        requireWorkerId(workerId)
        val video = find(jobId)
        if (video.leaseOwner != workerId) throw VideoLeaseLostException()
        requireLane(worker, video.lane ?: throw VideoLeaseLostException())
        return video
    }

    private class FolderListing(val keys: Set<String>, val bytes: Long)

    /** 조각 폴더 목록 — 키 집합과 합 크기. 한 번에 1000개씩 끝까지 */
    private fun listFolder(folder: String): FolderListing {
        var total = 0L
        val found = mutableSetOf<String>()
        var token: String? = null
        do {
            val listed = storage.list(ListObjectsRequest(prefix = folder, continuationToken = token, maxKeys = 1000))
            for (o in listed.objects) {
                total += o.sizeBytes ?: 0
                found += o.key.value
            }
            token = listed.nextContinuationToken.takeIf { listed.truncated }
        } while (token != null)
        return FolderListing(found, total)
    }

    /** 원본 · 호환 단 폴더가 온전한가 — 목록 · init · 조각 하나 이상, 합 크기가 보고와 같다. 합 크기를 돌려준다 */
    private fun requireFolder(folder: String, reportedBytes: Long): Long {
        val listing = listFolder(folder)
        val names = listing.keys.map { it.removePrefix(folder) }
        if ("index.m3u8" !in names || "init.mp4" !in names || names.none { it.endsWith(".m4s") }) throw VideoResultMismatchException("missing")
        if (listing.bytes != reportedBytes || listing.bytes <= 0) throw VideoResultMismatchException("size")
        return listing.bytes
    }

    private fun requireSize(key: String, reported: Long) {
        val actual = storage.metadata(ObjectKey(key))?.sizeBytes ?: throw VideoResultMismatchException("missing")
        if (actual != reported || actual <= 0) throw VideoResultMismatchException("size")
    }

    private fun requireWorkerId(workerId: String) {
        if (!WORKER_ID.matches(workerId)) throw VideoWorkerInvalidException("workerId")
    }

    private fun newBuild(): String = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))

    companion object {
        val WORKER_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")
        val RUNG_NAME = Regex("^[A-Za-z0-9]{1,16}$")
        const val MAX_UPLOAD_URLS = 200
        const val MAX_DETAIL = 500

        /** 짧은 변이 이 값 이하면 사다리를 안 만든다 — 아랫단이 원본보다 작아야 한다 */
        const val LADDER_FLOOR = 480
        const val COMPAT_BYTES_PER_MINUTE = 21L * 1024 * 1024
        const val LADDER_BYTES_PER_MINUTE = 32L * 1024 * 1024
    }
}

/** 임대 만료를 거둔다 — 일꾼이 집기 직전과 청소가 부른다. 끝내 실패한 줄에는 후속을 붙이고, 다시 줄 선 것이 있으면 깨운다. 호출자의 트랜잭션 안에서 */
internal object VideoLeases {
    fun expire(store: VideoStore, followUps: VideoFollowUps, waker: VideoWaker, props: VideoProperties, now: Instant, at: Instant): LeaseExpiry {
        val result = store.expireLeases(now, at)
        result.failed.forEach {
            followUps.finalize(it.id, if (it.remux) VideoFinalizeEvent.FAILED else VideoFinalizeEvent.TRACK_FAILED)
            followUps.finalize(it.id, VideoFinalizeEvent.SWEEP, props.cleanupDelay)
        }
        if (result.requeued > 0 && waker.enabled) followUps.wake()
        return result
    }
}
