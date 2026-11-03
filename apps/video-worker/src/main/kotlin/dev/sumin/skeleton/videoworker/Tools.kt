package dev.sumin.skeleton.videoworker

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** ffmpeg 가 0 이 아닌 값으로 끝났다 — [tail] 은 stderr 마지막 몇 줄(주소 · 토큰은 들어 있지 않다, 서명 주소는 가린다) */
class ToolFailure(val exit: Int, val tail: String) : RuntimeException("exit=$exit")

/** 일하던 중 임대를 잃었거나 시간이 다 됐다 */
class Cancelled(message: String) : RuntimeException(message)

/** ffmpeg · ffprobe 호출. 순수 계획(Plans.kt)이 만든 인자를 받아 돌린다. 격리 옵션은 계획이 싣고 여기는 프로세스만 다룬다 */
open class Tools(private val ffmpeg: String, private val ffprobe: String, private val nice: Int, private val timeout: Duration) {
    /**
     * 첫 인자 `ffmpeg` 를 설정의 실행 파일로 바꿔 돌린다. [cancel] 이 서면 프로세스를 죽인다.
     * [onProgress] 가 있으면 ffmpeg 가 처리한 출력 시각(ms)을 그때그때 알려 준다(`-progress`) — 이 줄들은 실패 꼬리에 섞이지 않는다
     */
    open fun run(args: List<String>, cwd: Path?, cancel: AtomicBoolean, onProgress: ((Long) -> Unit)? = null) {
        val progressArgs = if (onProgress != null) listOf("-progress", "pipe:1", "-nostats") else emptyList()
        val command = (if (nice > 0) listOf("nice", "-n", nice.toString()) else emptyList()) + listOf(ffmpeg) + progressArgs + args.drop(1)
        val process = ProcessBuilder(command).directory(cwd?.toFile()).redirectErrorStream(true).start()
        val tail = ArrayDeque<String>()
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                val progress = PROGRESS_LINE.matchEntire(line)
                if (progress != null) {
                    if (progress.groupValues[1] == "out_time_us") progress.groupValues[2].toLongOrNull()?.let { onProgress?.invoke(it / 1000) }
                } else {
                    synchronized(tail) { tail.addLast(line); if (tail.size > TAIL) tail.removeFirst() }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            while (true) {
                if (cancel.get()) throw Cancelled("임대를 잃어 ffmpeg 를 멈춘다")
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() > deadline) throw Cancelled("ffmpeg 시간 초과")
            }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        }
        reader.join(2000)
        if (process.exitValue() != 0) throw ToolFailure(process.exitValue(), synchronized(tail) { tail.joinToString("\n") }.replace(URL, "<url>"))
    }

    /**
     * ffprobe JSON. [input] 이 주소면 서명 주소로 범위 요청을 한다. [forceMov] 는 입력 형식을 mov 계열로 강제(확장자를 안 믿는다) —
     * 아니면 자동 판별(왜 안 읽히는지 가리는 용도)
     */
    open fun probe(input: String, headers: String?, forceMov: Boolean = true): MediaInfo {
        val isUrl = input.startsWith("http://") || input.startsWith("https://")
        val command = buildList {
            add(ffprobe); add("-v"); add("error"); add("-hide_banner")
            add("-protocol_whitelist"); add(if (isUrl) "file,http,https,tcp,tls,crypto" else "file")
            if (headers != null && isUrl) { add("-headers"); add(headers) }
            // 목록 밖 컨테이너 · 코덱은 열지 않는다(Allowed). 형식 진단(forceMov=false)도 컨테이너 목록은 건다 — 이름만 로그로 남기고 거절
            add("-format_whitelist"); add(Allowed.UPLOAD_FORMATS)
            add("-codec_whitelist"); add(Allowed.UPLOAD_CODECS)
            if (forceMov) { add("-f"); add("mov") }
            add("-print_format"); add("json"); add("-show_format"); add("-show_streams");
            add(input)
        }
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        // 출력을 따로 읽으며 기다린다 — 원격 입력이 멈추면 읽기부터 막혀 시간 한도가 죽은 코드가 되던 것
        val outFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val errFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(2, TimeUnit.MINUTES)) { process.destroyForcibly(); throw Cancelled("ffprobe 시간 초과") }
        val out = outFuture.get(10, TimeUnit.SECONDS)
        val err = errFuture.get(10, TimeUnit.SECONDS)
        if (process.exitValue() != 0) throw ToolFailure(process.exitValue(), err.takeLast(1500).replace(URL, "<url>"))
        return Probe.parse(out)
    }

    /** 공개된 원본 단(HLS 변형 목록 주소)을 읽는다 — 형식을 hls 로 강제하고 http 만 연다. 회전 표지 · 코덱은 init 조각에서 온다(비트레이트는 믿지 않는다) */
    open fun probeRemoteHls(playlistUrl: String, headers: String?): MediaInfo {
        val command = buildList {
            add(ffprobe); add("-v"); add("error"); add("-hide_banner")
            add("-protocol_whitelist"); add("file,http,https,tcp,tls,crypto")
            add("-codec_whitelist"); add(Allowed.BUILT_CODECS)
            if (headers != null) { add("-headers"); add(headers) }
            add("-f"); add("hls")
            add("-print_format"); add("json"); add("-show_format"); add("-show_streams")
            add(playlistUrl)
        }
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val outFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val errFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(2, TimeUnit.MINUTES)) { process.destroyForcibly(); throw Cancelled("ffprobe 시간 초과") }
        val out = outFuture.get(10, TimeUnit.SECONDS)
        val err = errFuture.get(10, TimeUnit.SECONDS)
        if (process.exitValue() != 0) throw ToolFailure(process.exitValue(), err.takeLast(1500).replace(URL, "<url>"))
        return Probe.parse(out).copy(bitrate = null)
    }

    /** 만든 HLS 변형 목록(로컬 파일)을 읽는다 — 조각 확장자(m4s)를 허용하고 로컬 파일만 연다 */
    open fun probeHls(playlist: String): MediaInfo {
        val process = ProcessBuilder(
            ffprobe, "-v", "error", "-hide_banner", "-protocol_whitelist", "file,crypto", "-codec_whitelist", Allowed.BUILT_CODECS, "-allowed_extensions", "ALL",
            "-print_format", "json", "-show_format", "-show_streams", playlist,
        ).start()
        val outFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val errFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(2, TimeUnit.MINUTES)) { process.destroyForcibly(); throw Cancelled("ffprobe 시간 초과") }
        val out = outFuture.get(10, TimeUnit.SECONDS)
        val err = errFuture.get(10, TimeUnit.SECONDS)
        if (process.exitValue() != 0) throw ToolFailure(process.exitValue(), err.takeLast(1500))
        return Probe.parse(out)
    }

    /** JPEG 포스터의 가로 · 세로 — 영상 프로브와 같은 ffprobe, 입력 형식은 자동 */
    fun probeImage(path: String): Pair<Int, Int> {
        val process = ProcessBuilder(
            ffprobe, "-v", "error", "-protocol_whitelist", "file", "-codec_whitelist", Allowed.POSTER_CODECS, "-select_streams", "v:0",
            "-show_entries", "stream=width,height", "-of", "csv=p=0:s=x", path,
        ).start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) throw ToolFailure(process.exitValue(), "포스터 크기를 못 읽었다")
        val (w, h) = out.split('x').map { it.trim().toInt() }
        return w to h
    }

    private companion object {
        const val TAIL = 12
        val URL = Regex("https?://\\S+")
        val PROGRESS_LINE = Regex("(frame|fps|stream_\\d+_\\d+_q|bitrate|total_size|out_time_us|out_time_ms|out_time|dup_frames|drop_frames|speed|progress)=(.*)")
    }
}
