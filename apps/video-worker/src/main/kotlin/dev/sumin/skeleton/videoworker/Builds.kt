package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import org.slf4j.LoggerFactory

/** 만든 조각 폴더 한 벌 — [folder] 는 `o-<해시16>` · `a-<해시16>` · `c-<해시16>`, [info] 는 만든 것을 ffprobe 로 다시 읽은 결과 */
internal class BuiltTier(val folder: String, val files: List<Path>, val sha: String, val info: MediaInfo) {
    val bytes: Long get() = files.sumOf { it.fileSize() }
}

/** 원본 단 + 포스터. [source] 는 올린 파일의 probe */
internal class BuiltRemux(val tier: BuiltTier, val source: MediaInfo, val poster: Path, val posterName: String, val posterSize: Pair<Int, Int>)

/**
 * 차선이 만드는 것 — ffmpeg 를 돌리고 검증해 폴더를 작업 디렉토리에 만든다. **올리기 · 서버 보고는 여기 없다**:
 * 서버 모드의 [Runner] 가 이 결과를 올리고 `complete` 하고, 로컬 모드([LocalMode])는 폴더에 복사한다. 같은 계획([Plans]) · 같은 검증을 두 길이 함께 쓴다
 */
internal class Builds(private val tools: Tools) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 올린 파일 [input] → 원본 단(`o-<해시>/`) + 포스터. [dir] 안에 `o` · `p.jpg` 를 만든다 */
    fun remux(input: Path, dir: Path, verifyDecode: Boolean, jobId: String, cancel: AtomicBoolean, progress: Progress): BuiltRemux {
        val source = probeLocal(input)
        Probe.rejection(source)?.let { throw JobFailure(it.code, it.detail) }
        val out = Files.createDirectories(dir.resolve("o"))
        tools.run(RemuxPlan.args(input.toString(), source), out, cancel, progress.band(5, 25, source.durationMs))
        if (verifyDecode) verifyDecode(input, dir, cancel, progress.band(25, 80, source.durationMs))
        val files = hlsFiles(out)
        val playlistText = Files.readString(out.resolve(HlsNames.PLAYLIST))
        ResultCheck.hlsPlaylist(playlistText, source.durationMs, files.map { it.fileName.toString() }.toSet())?.let { throw JobFailure(VideoFail.PROCESSING_FAILED, "원본 단: $it", retryable = true) }
        val longest = ResultCheck.longestSegmentSeconds(playlistText)
        if (longest > LONG_SEGMENT_WARN_SECONDS) log.warn("원본 단 조각이 길다 job={} 가장 긴 조각={}초(키프레임 간격 — 허용)", jobId, longest)
        // 회전 표지는 init 에 그대로 남아 프로브가 읽는다 — 비트레이트는 목록 프로브가 못 주니 올린 파일의 값을 쓴다
        val result = tools.probeHls(out.resolve(HlsNames.PLAYLIST).toString()).copy(bitrate = source.bitrate)
        verifyRemux(source, result)
        val sha = Transfer.sha256(files)
        val poster = dir.resolve("p.jpg")
        makePoster(input, poster, source, result.durationMs, dir, cancel)
        progress.raise(85)
        val posterName = "p-${Transfer.sha256(poster).take(16)}.jpg"
        val posterInfo = tools.probeImage(poster.toString())
        progress.raise(90)
        return BuiltRemux(BuiltTier("o-${sha.take(16)}", files, sha, result), source, poster, posterName, posterInfo)
    }

    /** 원본 단 목록 [url] → 소리 판(`a-<해시>/`). 소리 트랙을 복사만 하니 CPU 를 거의 안 쓴다 */
    fun audio(url: String, headers: String?, dir: Path, cancel: AtomicBoolean, progress: Progress): BuiltTier {
        val info = tools.probeRemoteHls(url, headers)
        // 소리가 없는 영상은 서버가 원본 단 완료 때 SKIPPED 로 닫는다 — 여기까지 왔으면 다시 해도 같다
        if (!info.hasAudio) throw JobFailure(VideoFail.PROCESSING_FAILED, "원본 단에 소리 트랙이 없다")
        progress.raise(3)
        val out = Files.createDirectories(dir.resolve("a"))
        tools.run(AudioPlan.args(url, headers), out, cancel, progress.band(3, 90, info.durationMs))
        val files = hlsFiles(out)
        val playlistText = Files.readString(out.resolve(HlsNames.PLAYLIST))
        ResultCheck.hlsPlaylist(playlistText, info.durationMs, files.map { it.fileName.toString() }.toSet())?.let { throw JobFailure(VideoFail.PROCESSING_FAILED, "소리 판: $it", retryable = true) }
        val made = tools.probeHls(out.resolve(HlsNames.PLAYLIST).toString())
        ResultCheck.audio(info, made)?.let { throw JobFailure(VideoFail.PROCESSING_FAILED, it, retryable = true) }
        val sha = Transfer.sha256(files)
        return BuiltTier("a-${sha.take(16)}", files, sha, made)
    }

    /** 원본 단 목록 [url] → 호환 단(H.264 720p, `c-<해시>/`) */
    fun compat(url: String, headers: String?, dir: Path, cancel: AtomicBoolean, progress: Progress): BuiltTier {
        val info = tools.probeRemoteHls(url, headers)
        progress.raise(3)
        val out = Files.createDirectories(dir.resolve("c"))
        tools.run(CompatPlan.args(url, info, headers), out, cancel, progress.band(3, 90, info.durationMs))
        val files = hlsFiles(out)
        val playlistText = Files.readString(out.resolve(HlsNames.PLAYLIST))
        ResultCheck.hlsPlaylist(playlistText, info.durationMs, files.map { it.fileName.toString() }.toSet())?.let { throw JobFailure(VideoFail.PROCESSING_FAILED, "호환 단: $it", retryable = true) }
        val made = tools.probeHls(out.resolve(HlsNames.PLAYLIST).toString())
        ResultCheck.compat(info, made)?.let { throw JobFailure(VideoFail.PROCESSING_FAILED, it, retryable = true) }
        val sha = Transfer.sha256(files)
        return BuiltTier("c-${sha.take(16)}", files, sha, made)
    }

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

    /** remux 결과가 입력과 같은 영상인지 — 길이 · 스트림 수 · 코덱 */
    private fun verifyRemux(source: MediaInfo, result: MediaInfo) {
        val tolerance = maxOf(500L, source.durationMs / 50)
        if (kotlin.math.abs(result.durationMs - source.durationMs) > tolerance) throw JobFailure(VideoFail.PROCESSING_FAILED, "길이가 다르다")
        if (result.videoStreams != 1 || result.audioStreams > 1) throw JobFailure(VideoFail.PROCESSING_FAILED, "스트림 수가 다르다")
        if (result.videoCodec != source.videoCodec) throw JobFailure(VideoFail.PROCESSING_FAILED, "코덱이 바뀌었다")
    }

    private companion object {
        const val LONG_SEGMENT_WARN_SECONDS = 30.0
        val WHITELIST_HIT = Regex("not on whitelist", RegexOption.IGNORE_CASE)
    }
}
