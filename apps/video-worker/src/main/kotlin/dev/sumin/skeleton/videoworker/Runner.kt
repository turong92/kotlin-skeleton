package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import org.slf4j.LoggerFactory

/** 일 하나를 못 끝내는 이유 — [retryable] 이면 서버가 시도가 남은 만큼 다시 줄 세운다 */
class JobFailure(val code: String, val detail: String, val retryable: Boolean = false) : RuntimeException(detail)

/** 일 하나의 진행률(0..99, 줄지 않는다) — heartbeat 가 실어 보낸다. 끝은 complete 가 알린다 */
private class Progress {
    private val value = AtomicInteger(-1)

    fun raise(percent: Int) {
        value.accumulateAndGet(percent.coerceIn(0, 99), ::maxOf)
    }

    /** 모르면 null */
    fun get(): Int? = value.get().takeIf { it >= 0 }

    /** ffmpeg 가 알려 주는 처리 시각(ms)을 [from]..[to] 퍼센트 구간에 편다 */
    fun band(from: Int, to: Int, totalMs: Long): (Long) -> Unit = { ms ->
        raise(from + ((to - from) * ms.coerceIn(0, totalMs) / maxOf(1L, totalMs)).toInt())
    }
}

/**
 * claim → 일 → 올리기 → complete / fail 한 바퀴. 일 하나마다 임시 폴더를 만들고 끝나면(실패해도) 지운다.
 * heartbeat 스레드가 임대를 늘리고, 잃으면 [cancel] 을 세워 ffmpeg 를 멈춘다. 서명 주소 · 토큰은 로그에 찍지 않는다
 */
class Runner(
    private val config: WorkerConfig,
    private val api: WorkerApi,
    private val transfer: Transfer,
    private val tools: Tools,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 일이 없을 때까지(204) 반복한다 — `once` 모양(Cloud Run 작업). 끝낸 일 수 */
    fun runUntilIdle(): Int {
        var done = 0
        while (true) {
            val job = api.claim(config.lanes) ?: return done
            handle(job)
            done++
        }
    }

    /** 상주(poll) — 큐가 비면 쉬었다가 다시 집는다. 서버가 안 열려도 죽지 않는다 */
    fun runForever(stop: AtomicBoolean = AtomicBoolean(false)) {
        while (!stop.get()) {
            val worked = try {
                runUntilIdle() > 0
            } catch (e: Exception) {
                log.warn("집기 실패 — {}", e.javaClass.simpleName)
                false
            }
            if (!worked) Thread.sleep(config.pollInterval.toMillis())
        }
    }

    fun handle(job: Job) {
        log.info("일 시작 job={} lane={} 시도={}", job.jobId, job.lane, job.attempt)
        val cancel = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val progress = Progress()
        val beat = startHeartbeat(job.jobId, cancel, timedOut, progress, Thread.currentThread())
        val dir = Files.createDirectories(config.workDir).let { Files.createTempDirectory(config.workDir, "job-") }
        try {
            checkSpace(job)
            when (job.lane) {
                "REMUX" -> remux(job, dir, cancel, progress)
                "COMPAT" -> compat(job, dir, cancel, progress)
                "LADDER" -> ladder(job, dir, cancel, progress)
                else -> throw JobFailure(VideoFail.PROCESSING_FAILED, "모르는 차선 ${job.lane}")
            }
            log.info("일 끝 job={} lane={}", job.jobId, job.lane)
        } catch (e: LeaseLostException) {
            log.warn("임대를 잃었다 job={} — 일을 버린다", job.jobId)
        } catch (e: InterruptedException) {
            // 일 시간 한도 감시가 막힌 전송 · ffmpeg 대기를 깨웠다 — 한도 때문이 아니면 정말 멈추라는 신호이니 그대로 올린다
            if (!timedOut.get()) throw e
            reportTimeout(job)
        } catch (e: Cancelled) {
            if (timedOut.get()) reportTimeout(job)
            else if (!cancel.get()) report(job, VideoFail.PROCESSING_FAILED, e.message ?: "취소", retryable = true)
            else log.warn("임대를 잃어 멈췄다 job={}", job.jobId)
        } catch (e: JobFailure) {
            report(job, e.code, e.detail, e.retryable)
        } catch (e: ToolFailure) {
            val corrupt = CORRUPT_HINT.containsMatchIn(e.tail)
            report(job, if (corrupt) VideoFail.CORRUPT else VideoFail.PROCESSING_FAILED, e.tail.takeLast(300), retryable = !corrupt)
        } catch (e: Exception) {
            // 네트워크 · 디스크 같은 일시 오류 — 다시 해 볼 만하다
            report(job, VideoFail.PROCESSING_FAILED, e.javaClass.simpleName, retryable = true)
        } finally {
            beat.interrupt()
            if (timedOut.get()) Thread.interrupted() // 감시가 건 인터럽트 표시를 치운다 — 다음 일 · 실패 알림이 영향을 안 받게
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private fun reportTimeout(job: Job) =
        report(job, VideoFail.PROCESSING_FAILED, "일 시간 한도(${config.jobTimeout.toMinutes()}분)를 넘었다", retryable = true)

    // ---- 차선 ----

    /** 올린 파일 → 원본 단(재인코딩 없는 fMP4 HLS 조각 폴더 `o-<해시>/`) + 포스터 */
    private fun remux(job: Job, dir: Path, cancel: AtomicBoolean, progress: Progress) {
        val input = dir.resolve("in")
        transfer.download(job.inputUrl, input, job.inputBytes)
        progress.raise(5)
        val source = probeLocal(input)
        Probe.rejection(source)?.let { throw JobFailure(it.code, it.detail) }
        val out = Files.createDirectories(dir.resolve("o"))
        tools.run(RemuxPlan.args(input.toString(), source), out, cancel, progress.band(5, 25, source.durationMs))
        if (job.verifyDecode) verifyDecode(input, dir, cancel, progress.band(25, 80, source.durationMs))
        val files = hlsFiles(out)
        val playlistText = Files.readString(out.resolve(HlsNames.PLAYLIST))
        ResultCheck.hlsPlaylist(playlistText, source.durationMs, files.map { it.fileName.toString() }.toSet())?.let { throw JobFailure(VideoFail.PROCESSING_FAILED, "원본 단: $it", retryable = true) }
        val longest = ResultCheck.longestSegmentSeconds(playlistText)
        if (longest > LONG_SEGMENT_WARN_SECONDS) log.warn("원본 단 조각이 길다 job={} 가장 긴 조각={}초(키프레임 간격 — 허용)", job.jobId, longest)
        // 회전 표지는 init 에 그대로 남아 프로브가 읽는다 — 비트레이트는 목록 프로브가 못 주니 올린 파일의 값을 쓴다
        val result = tools.probeHls(out.resolve(HlsNames.PLAYLIST).toString()).copy(bitrate = source.bitrate)
        verifyRemux(source, result)
        val sha = Transfer.sha256(files)
        val folder = "o-${sha.take(16)}"
        val poster = dir.resolve("p.jpg")
        makePoster(input, poster, source, result.durationMs, dir, cancel)
        progress.raise(85)
        val posterSha = Transfer.sha256(poster)
        val posterName = "p-${posterSha.take(16)}.jpg"
        val posterInfo = tools.probeImage(poster.toString())
        progress.raise(90)
        upload(job, folderUploads(folder, files) + (posterName to poster))
        api.complete(
            job.jobId,
            CompleteBody(
                workerId = config.workerId,
                orig = ResultFile("$folder/${HlsNames.PLAYLIST}", files.sumOf { it.fileSize() }, sha),
                poster = ResultFile(posterName, poster.fileSize(), null, posterInfo.first, posterInfo.second),
                probe = ProbeResult(
                    container = source.container, codec = result.codecString, width = result.displayWidth, height = result.displayHeight,
                    rotation = result.rotation, durationMs = result.durationMs, fps = result.fps, bitrate = result.bitrate, audioCodec = result.audioCodec,
                ),
                compatible = result.compatible,
            ),
        )
    }

    /** 원본 단 → 호환 단(H.264 720p fMP4 HLS 폴더 `c-<해시>/`). 공개된 원본 단 주소를 읽는다 — 내려받을 디스크가 필요 없다 */
    private fun compat(job: Job, dir: Path, cancel: AtomicBoolean, progress: Progress) {
        val src = transfer.resolve(job.inputUrl)
        val info = tools.probeRemoteHls(src.url, src.ffmpegHeaders)
        progress.raise(3)
        val out = Files.createDirectories(dir.resolve("c"))
        tools.run(CompatPlan.args(src.url, info, src.ffmpegHeaders), out, cancel, progress.band(3, 90, info.durationMs))
        val files = hlsFiles(out)
        val playlistText = Files.readString(out.resolve(HlsNames.PLAYLIST))
        ResultCheck.hlsPlaylist(playlistText, info.durationMs, files.map { it.fileName.toString() }.toSet())?.let { throw JobFailure(VideoFail.PROCESSING_FAILED, "호환 단: $it", retryable = true) }
        val made = tools.probeHls(out.resolve(HlsNames.PLAYLIST).toString())
        ResultCheck.compat(info, made)?.let { throw JobFailure(VideoFail.PROCESSING_FAILED, it, retryable = true) }
        val sha = Transfer.sha256(files)
        val folder = "c-${sha.take(16)}"
        progress.raise(93)
        upload(job, folderUploads(folder, files))
        api.complete(job.jobId, CompleteBody(config.workerId, compat = ResultFile("$folder/${HlsNames.PLAYLIST}", files.sumOf { it.fileSize() }, sha)))
    }

    /** 원본 단 → HLS 아랫단 (6초 조각) + 마스터 목록에 원본 단(1080 이하일 때). 폴더째 올린다 */
    private fun ladder(job: Job, dir: Path, cancel: AtomicBoolean, progress: Progress) {
        val build = job.build ?: throw JobFailure(VideoFail.PROCESSING_FAILED, "사다리 빌드 id 가 없다")
        val origFolder = job.origFolder ?: throw JobFailure(VideoFail.PROCESSING_FAILED, "원본 단 폴더 이름이 없다")
        val src = transfer.resolve(job.inputUrl)
        val info = tools.probeRemoteHls(src.url, src.ffmpegHeaders)
        progress.raise(3)
        val rungs = LadderPlan.rungs(info)
        if (rungs.isEmpty()) throw JobFailure(VideoFail.PROCESSING_FAILED, "만들 판이 없다")
        val out = Files.createDirectories(dir.resolve("h"))
        tools.run(LadderPlan.args(src.url, info, src.ffmpegHeaders), out, cancel, progress.band(3, 90, info.durationMs))
        val files = out.listDirectoryEntries().filter { it.isRegularFile() }.sortedBy { it.fileName.toString() }
        if (files.none { it.fileName.toString() == "master.m3u8" }) throw JobFailure(VideoFail.PROCESSING_FAILED, "master.m3u8 이 안 만들어졌다")
        verifyLadder(info, rungs, out, files)
        if (OriginalRung.eligible(info)) {
            val master = out.resolve("master.m3u8")
            Files.writeString(master, OriginalRung.patch(Files.readString(master), origFolder, info, job.codec, job.inputBytes))
        }
        progress.raise(93)
        upload(job, files.map { "h-$build/${it.fileName}" to it })
        api.complete(job.jobId, CompleteBody(config.workerId, ladderBuild = build, rungs = rungs.map { LadderRungResult(it.name, it.height, it.bandwidth) }))
    }

    // ---- 도움 ----

    /**
     * 공개 영상의 정상 여부 — 쓰는 두 스트림을 끝까지 디코드해 본다. 디코드 오류(손상 · 조작)만 거절이고 다시 해도 같다.
     * ffmpeg 가 신호로 죽은 것(SIGKILL 137 · SIGTERM 143 — 메모리 부족 등)은 파일 탓이 아니니 재시도 가능한 실패로 둔다.
     * 디코드 오류의 종료 코드는 오류 번호의 낮은 바이트(예: 183)라 범위로 가를 수 없다
     */
    private fun verifyDecode(input: Path, dir: Path, cancel: AtomicBoolean, onProgress: (Long) -> Unit) {
        try {
            tools.run(DecodeCheckPlan.args(input.toString()), dir, cancel, onProgress)
        } catch (e: ToolFailure) {
            if (e.exit == 137 || e.exit == 143) throw JobFailure(VideoFail.PROCESSING_FAILED, "디코드 검사가 죽었다 exit=${e.exit}", retryable = true)
            throw JobFailure(VideoFail.CORRUPT, "디코드 실패: ${e.tail.takeLast(250)}")
        }
    }

    /** 포스터 — 표지 그림이 있으면 그것(못 읽으면 영상 프레임으로: 표지 때문에 멀쩡한 영상을 거절하지 않는다), 없으면 영상 프레임 */
    private fun makePoster(input: Path, poster: Path, source: MediaInfo, durationMs: Long, dir: Path, cancel: AtomicBoolean) {
        val cover = source.coverStream
        if (cover != null) {
            try {
                tools.run(PosterPlan.args(input.toString(), poster.toString(), durationMs, cover), dir, cancel)
                return
            } catch (e: ToolFailure) {
                log.warn("표지 그림을 못 읽어 영상 프레임으로 포스터를 만든다")
            }
        }
        tools.run(PosterPlan.args(input.toString(), poster.toString(), durationMs), dir, cancel)
    }

    private fun upload(job: Job, files: List<Pair<String, Path>>) {
        for (chunk in files.chunked(UPLOAD_BATCH)) {
            val urls = api.uploadUrls(job.jobId, chunk.map { it.first }).associateBy { it.name }
            for ((name, path) in chunk) transfer.upload(urls[name] ?: throw JobFailure(VideoFail.PROCESSING_FAILED, "주소를 못 받았다"), path)
        }
    }

    /** 올린 파일을 읽는다 — 안 읽히면 형식이 다른 건지 깨진 건지 가린다. 목록 밖 컨테이너 · 코덱은 열리기 전에 막혀 못 받는 형식으로 끝난다(재시도 없음) */
    private fun probeLocal(path: Path): MediaInfo = try {
        tools.probe(path.toString(), null)
    } catch (e: ToolFailure) {
        notAllowed(e.tail)?.let { throw JobFailure(VideoFail.UNSUPPORTED_FORMAT, it) }
        val other = try {
            tools.probe(path.toString(), null, forceMov = false)
        } catch (d: ToolFailure) {
            notAllowed(d.tail)?.let { throw JobFailure(VideoFail.UNSUPPORTED_FORMAT, it) }
            null
        }
        if (other != null) throw JobFailure(VideoFail.UNSUPPORTED_FORMAT, "container=${other.container}")
        throw JobFailure(VideoFail.CORRUPT, e.tail.takeLast(300))
    }

    /** ffprobe 가 `Codec (x) not on whitelist` · `[컨테이너] Format not on whitelist` 로 막았으면 그 이름이 든 짧은 사유, 아니면 null */
    private fun notAllowed(tail: String): String? {
        val line = tail.lineSequence().firstOrNull { WHITELIST_HIT.containsMatchIn(it) } ?: return null
        return line.replace(Regex("0x[0-9a-f]+"), "").replace(Regex("\\s+"), " ").trim().take(200)
    }

    /** 조각 폴더의 파일 — 변형 목록 · init · 조각. 목록이 없으면 ffmpeg 가 아무것도 못 냈다 */
    private fun hlsFiles(dir: Path): List<Path> {
        val files = dir.listDirectoryEntries().filter { it.isRegularFile() }.sortedBy { it.fileName.toString() }
        val names = files.map { it.fileName.toString() }
        if (HlsNames.PLAYLIST !in names || HlsNames.INIT !in names) throw JobFailure(VideoFail.PROCESSING_FAILED, "조각 폴더가 비었다: $names".take(300), retryable = true)
        return files
    }

    /** 올릴 이름 — 조각 · init 먼저, 변형 목록은 맨 끝(목록이 가리키는 파일이 모두 올라간 뒤에 목록이 보이게) */
    private fun folderUploads(folder: String, files: List<Path>): List<Pair<String, Path>> =
        files.sortedBy { if (it.fileName.toString() == HlsNames.PLAYLIST) 1 else 0 }.map { "$folder/${it.fileName}" to it }

    /** remux 결과가 입력과 같은 영상인지 — 길이 · 스트림 수 · 코덱 */
    private fun verifyRemux(source: MediaInfo, result: MediaInfo) {
        val tolerance = maxOf(500L, source.durationMs / 50)
        if (kotlin.math.abs(result.durationMs - source.durationMs) > tolerance) throw JobFailure(VideoFail.PROCESSING_FAILED, "길이가 다르다")
        if (result.videoStreams != 1 || result.audioStreams > 1) throw JobFailure(VideoFail.PROCESSING_FAILED, "스트림 수가 다르다")
        if (result.videoCodec != source.videoCodec) throw JobFailure(VideoFail.PROCESSING_FAILED, "코덱이 바뀌었다")
    }

    /** 만든 사다리가 원본과 같은 영상인지 — 마스터 · 판마다 변형 목록 · 조각 수 · 조각 파일 · 길이 · 영상 소리 트랙. 어긋나면 올리지 않고 재시도 가능한 실패 */
    private fun verifyLadder(source: MediaInfo, rungs: List<Rung>, dir: Path, files: List<Path>) {
        fun bad(why: String): Nothing = throw JobFailure(VideoFail.PROCESSING_FAILED, "사다리: $why", retryable = true)
        val names = files.map { it.fileName.toString() }.toSet()
        ResultCheck.ladderMaster(Files.readString(dir.resolve("master.m3u8")), rungs.size)?.let { bad(it) }
        rungs.forEachIndexed { i, rung ->
            val playlist = dir.resolve("v$i.m3u8")
            if (!playlist.isRegularFile()) bad("v$i.m3u8 이 없다")
            ResultCheck.ladderPlaylist(Files.readString(playlist), source.durationMs, names)?.let { bad("${rung.height}: $it") }
            val made = try { tools.probeHls(playlist.toString()) } catch (e: ToolFailure) { bad("${rung.height} 을 읽지 못했다") }
            ResultCheck.ladderRung(source, rung, made)?.let { bad(it) }
        }
    }

    /** 임시 폴더에 남은 자리보다 결과가 클 것 같으면 시작도 안 한다 */
    private fun checkSpace(job: Job) {
        Files.createDirectories(config.workDir)
        val free = Files.getFileStore(config.workDir).usableSpace
        val need = if (job.lane == "REMUX") job.inputBytes + job.expectedOutputBytes else job.expectedOutputBytes
        if (need > free) throw JobFailure(VideoFail.TOO_BIG_FOR_WORKER, "필요 $need 바이트 > 여유 $free")
    }

    private fun report(job: Job, code: String, detail: String, retryable: Boolean) {
        log.warn("일 실패 job={} lane={} code={} retryable={}", job.jobId, job.lane, code, retryable)
        try {
            api.fail(job.jobId, code, detail, retryable)
        } catch (e: LeaseLostException) {
            log.warn("실패를 알리려 했으나 임대가 없다 job={}", job.jobId)
        }
    }

    /**
     * 임대 연장(heartbeat) + 일 단위 벽시계 한도 감시. 한도를 넘기면 [cancel] 을 세워 ffmpeg 를 죽이고, 막힌 전송 · 대기를 [worker] 인터럽트로 깨운 뒤
     * 연장을 멈춘다 — 실패 알림마저 못 가도 임대(서버 10분)가 저절로 만료돼 서버가 되돌린다(시도 상한 뒤 FAILED → 실패 급증 알림)
     */
    private fun startHeartbeat(jobId: String, cancel: AtomicBoolean, timedOut: AtomicBoolean, progress: Progress, worker: Thread): Thread {
        val deadline = System.nanoTime() + config.jobTimeout.toNanos()
        return Thread {
            try {
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        log.warn("일 시간 한도를 넘었다 job={} — ffmpeg 를 멈춘다", jobId)
                        timedOut.set(true)
                        cancel.set(true)
                        worker.interrupt()
                        return@Thread
                    }
                    Thread.sleep(minOf(config.heartbeatInterval.toMillis(), remaining / 1_000_000 + 1))
                    if (System.nanoTime() >= deadline) continue
                    try {
                        api.heartbeat(jobId, progress.get())
                    } catch (e: LeaseLostException) {
                        cancel.set(true)
                        return@Thread
                    } catch (e: Exception) {
                        log.warn("heartbeat 실패 job={} — {}", jobId, e.javaClass.simpleName)
                    }
                }
            } catch (_: InterruptedException) {
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private companion object {
        const val UPLOAD_BATCH = 100
        const val LONG_SEGMENT_WARN_SECONDS = 30.0
        val WHITELIST_HIT = Regex("not on whitelist", RegexOption.IGNORE_CASE)
        val CORRUPT_HINT = Regex("Invalid data found|moov atom not found|corrupt|truncated|Invalid NAL|error while decoding", RegexOption.IGNORE_CASE)
    }
}
