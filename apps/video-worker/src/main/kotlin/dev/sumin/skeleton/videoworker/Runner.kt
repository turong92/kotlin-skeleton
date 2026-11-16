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
internal class Progress {
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
    private val builds = Builds(tools)

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
                "AUDIO" -> audio(job, dir, cancel, progress)
                "COMPAT" -> compat(job, dir, cancel, progress)
                "LADDER" -> ladder(job, dir, cancel, progress)
                "ANIMATED" -> animated(job, dir, cancel, progress)
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

    // ---- 차선 ---- (만드는 쪽은 [Builds] — 로컬 모드와 같은 코드. 여기는 내려받기 · 올리기 · 보고만)

    /** 올린 파일 → 원본 단(재인코딩 없는 fMP4 HLS 조각 폴더 `o-<해시>/`) + 포스터 */
    private fun remux(job: Job, dir: Path, cancel: AtomicBoolean, progress: Progress) {
        val input = dir.resolve("in")
        transfer.download(job.inputUrl, input, job.inputBytes)
        progress.raise(5)
        val built = builds.remux(input, dir, job.verifyDecode, job.jobId, cancel, progress)
        val tier = built.tier
        upload(job, folderUploads(tier.folder, tier.files) + (built.posterName to built.poster))
        api.complete(
            job.jobId,
            CompleteBody(
                workerId = config.workerId,
                orig = ResultFile("${tier.folder}/${HlsNames.PLAYLIST}", tier.bytes, tier.sha),
                poster = ResultFile(built.posterName, built.poster.fileSize(), null, built.posterSize.first, built.posterSize.second),
                probe = ProbeResult(
                    container = built.source.container, codec = tier.info.codecString, width = tier.info.displayWidth, height = tier.info.displayHeight,
                    rotation = tier.info.rotation, durationMs = tier.info.durationMs, fps = tier.info.fps, bitrate = tier.info.bitrate, audioCodec = tier.info.audioCodec,
                ),
                compatible = tier.info.compatible,
            ),
        )
    }

    /** 원본 단 → 소리 판(영상 없는 AAC fMP4 HLS 폴더 `a-<해시>/`) */
    private fun audio(job: Job, dir: Path, cancel: AtomicBoolean, progress: Progress) {
        val src = transfer.resolve(job.inputUrl)
        val tier = builds.audio(src.url, src.ffmpegHeaders, dir, cancel, progress)
        progress.raise(93)
        upload(job, folderUploads(tier.folder, tier.files))
        api.complete(job.jobId, CompleteBody(config.workerId, audio = ResultFile("${tier.folder}/${HlsNames.PLAYLIST}", tier.bytes, tier.sha)))
    }

    /** 원본 단 → 호환 단(H.264 720p fMP4 HLS 폴더 `c-<해시>/`). 공개된 원본 단 주소를 읽는다 — 내려받을 디스크가 필요 없다 */
    private fun compat(job: Job, dir: Path, cancel: AtomicBoolean, progress: Progress) {
        val src = transfer.resolve(job.inputUrl)
        val tier = builds.compat(src.url, src.ffmpegHeaders, dir, cancel, progress)
        progress.raise(93)
        upload(job, folderUploads(tier.folder, tier.files))
        api.complete(job.jobId, CompleteBody(config.workerId, compat = ResultFile("${tier.folder}/${HlsNames.PLAYLIST}", tier.bytes, tier.sha)))
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

    /**
     * 움직이는 GIF · WebP → 소리 없는 MP4(불투명) 또는 움직이는 WebP(투명). 서버는 이미 사본을 표시본으로 쓰고 있다 -
     * 여기서 올리지 않고 끝내는 모든 길(못 읽음 · 변환본이 더 큼)은 **다시 하지 않는 실패**로 알려 사본이 계속 쓰이게 한다
     */
    private fun animated(job: Job, dir: Path, cancel: AtomicBoolean, progress: Progress) {
        val input = dir.resolve("in")
        transfer.download(job.inputUrl, input, job.inputBytes)
        progress.raise(10)
        val kind = AnimatedKind.sniff(Files.newInputStream(input).use { it.readNBytes(12) }) ?: throw JobFailure(VideoFail.UNSUPPORTED_FORMAT, "GIF 도 WebP 도 아니다")
        val out = Files.createDirectories(dir.resolve("a"))
        try {
            tools.run(AnimatedPlan.args(input.toString(), kind), out, cancel)
        } catch (e: ToolFailure) {
            // 움직이는 WebP 디코더는 최근 ffmpeg 에만 있다 - 못 풀면 파일 탓이 아니라 이 일꾼 탓이니 변환하지 않는다(사본을 쓴다)
            if (kind == AnimatedKind.WEBP) throw JobFailure(VideoFail.UNSUPPORTED_FORMAT, "이 ffmpeg 는 움직이는 WebP 를 못 푼다")
            throw e
        }
        progress.raise(85)
        val alpha = AnimatedPlan.parseAlpha(Files.readString(out.resolve(AnimatedPlan.ALPHA_STATS)))
        // 정지 이미지로만 풀렸다(움직이는 WebP 를 첫 장만 읽는 ffmpeg) - 서버가 움직이는 이미지로 보냈으니 한 장이면 못 푼 것이다
        if (alpha.frames < 2) throw JobFailure(VideoFail.UNSUPPORTED_FORMAT, "프레임 ${alpha.frames}장만 풀렸다")
        val transparent = alpha.minAlpha < 255
        // 투명한 움직이는 WebP 는 이미 가장 알맞은 형식이다(결과 이름이 사본 `display.webp` 와 겹치기도 한다)
        if (transparent && kind == AnimatedKind.WEBP) throw JobFailure(VideoFail.PROCESSING_FAILED, "이미 투명한 움직이는 WebP - 변환하지 않는다")
        val name = if (transparent) AnimatedPlan.WEBP else AnimatedPlan.MP4
        val result = out.resolve(name)
        val size = if (result.isRegularFile()) result.fileSize() else 0L
        if (size <= 0) throw JobFailure(VideoFail.PROCESSING_FAILED, "변환본이 안 만들어졌다", retryable = true)
        if (size >= job.inputBytes) throw JobFailure(VideoFail.PROCESSING_FAILED, "변환본($size)이 원본(${job.inputBytes})보다 작지 않다 - 사본을 쓴다")
        upload(job, listOf(name to result))
        api.complete(job.jobId, CompleteBody(config.workerId, display = ResultFile(name, size)))
    }

    // ---- 도움 ----

    private fun upload(job: Job, files: List<Pair<String, Path>>) {
        for (chunk in files.chunked(UPLOAD_BATCH)) {
            val urls = api.uploadUrls(job.jobId, chunk.map { it.first }).associateBy { it.name }
            for ((name, path) in chunk) transfer.upload(urls[name] ?: throw JobFailure(VideoFail.PROCESSING_FAILED, "주소를 못 받았다"), path)
        }
    }

    /** 올릴 이름 — 조각 · init 먼저, 변형 목록은 맨 끝(목록이 가리키는 파일이 모두 올라간 뒤에 목록이 보이게) */
    private fun folderUploads(folder: String, files: List<Path>): List<Pair<String, Path>> =
        files.sortedBy { if (it.fileName.toString() == HlsNames.PLAYLIST) 1 else 0 }.map { "$folder/${it.fileName}" to it }

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
        val need = if (job.lane == "REMUX" || job.lane == "ANIMATED") job.inputBytes + job.expectedOutputBytes else job.expectedOutputBytes
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
        val CORRUPT_HINT = Regex("Invalid data found|moov atom not found|corrupt|truncated|Invalid NAL|error while decoding", RegexOption.IGNORE_CASE)
    }
}
