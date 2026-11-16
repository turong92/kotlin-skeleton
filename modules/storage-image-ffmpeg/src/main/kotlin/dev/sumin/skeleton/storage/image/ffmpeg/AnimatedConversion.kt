package dev.sumin.skeleton.storage.image.ffmpeg

import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.UploadObjectRequest
import dev.sumin.skeleton.storage.image.AnimatedDisplays
import dev.sumin.skeleton.storage.image.ImageStatusStore
import dev.sumin.skeleton.storage.image.ImageVariantProcessor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** ffmpeg 를 못 띄웠거나 · 시간 상한을 넘었거나 · 오류로 끝났다 - 던져서 큐가 다시 하게 한다(소진되면 DEAD, 사본은 그대로) */
class FfmpegFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 움직이는 이미지 하나의 표시본 변환 - 원본을 임시 폴더로 받아 ffmpeg 를 한 번 돌리고 결과(MP4 또는 움직이는 WebP)를 `<폴더>display.<ext>` 로 올려 [AnimatedDisplays.use] 로 더한다.
 * **멱등이다**: 변환본이 이미 올라가 있으면(올리고 상태 줄에 더하기 전에 죽은 일 · 같은 요청이 또 온 일) 다시 인코딩하지 않고 [AnimatedDisplays.use] 만 한다 - use 가 이미 더해져 있어도 같은 결과를 낸다.
 * 못 만들거나 만들 필요가 없는 모든 길은 [Outcome.Skipped] 로 끝난다(사본이 계속 쓰인다) - 던지는 것은 다시 해 볼 만한 실패(ffmpeg 를 못 띄움 · 시간 초과 · 신호 종료 = [FfmpegFailedException] · 저장소 오류)뿐이다. ffmpeg 의 오류 종료는 같은 입력이면 매번 같아 [Outcome.Skipped] 다.
 */
class AnimatedConversion(
    private val storage: ObjectStorage,
    private val store: ImageStatusStore,
    private val displays: AnimatedDisplays,
    private val props: FfmpegProperties,
) {
    sealed interface Outcome {
        data class Converted(val ext: String) : Outcome
        data class Skipped(val reason: String) : Outcome
    }

    private val log = LoggerFactory.getLogger(javaClass)

    fun convert(keyHash: String): Outcome {
        val copyExt = displays.copyExtension(keyHash) ?: return skipped(keyHash, "not a ready animated image")
        val row = store.find(keyHash) ?: return skipped(keyHash, "no status row")
        val folder = displays.folder(keyHash)

        for ((name, ext) in RESULTS) {
            if (ext == copyExt || (storage.metadata(ObjectKey("$folder$name"))?.sizeBytes ?: 0L) <= 0L) continue
            return if (displays.use(keyHash, ext)) Outcome.Converted(ext) else skipped(keyHash, "image is gone")
        }

        val source = ObjectKey(row.objectKey)
        val size = storage.metadata(source)?.sizeBytes ?: 0L
        if (size <= 0L) return skipped(keyHash, "source is missing or empty")
        if (size > props.maxSourceBytes) return skipped(keyHash, "source $size bytes is over max-source-bytes ${props.maxSourceBytes}")
        val head = storage.read(source, 0L until minOf(size, HEAD_BYTES)).use { it.readNBytes(HEAD_BYTES.toInt()) }
        val kind = AnimatedKind.sniff(head) ?: return skipped(keyHash, "not a GIF or WebP")
        // 상태 줄의 크기는 머리에서 읽은 값이다(디코드 전) - 압축된 거대 그림은 ffmpeg 를 부르기 전에 막는다
        val pixels = (row.width ?: 0).toLong() * (row.height ?: 0)
        if (pixels > props.maxPixels) return skipped(keyHash, "${row.width}x${row.height} is over max-pixels ${props.maxPixels}")

        val work = Files.createTempDirectory("ffmpeg-animated-")
        try {
            val input = work.resolve("in")
            storage.read(source).use { Files.copy(it, input) }
            if (Files.size(input) > props.maxSourceBytes) return skipped(keyHash, "source grew past max-source-bytes")
            run(AnimatedPlan.args(props.path, input.toString(), kind, props, size), work)?.let { failure ->
                // 오류 종료는 같은 입력이면 매번 같다 - 고장 난 파일이거나(움직이는 WebP 는 8.0 미만 ffmpeg 가 못 푸는 경우도) 다시 해도 소용없다. 사본을 쓴다
                return skipped(keyHash, "ffmpeg could not convert this $kind: $failure")
            }
            return publish(keyHash, copyExt, folder, work, size, kind)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private fun publish(keyHash: String, copyExt: String, folder: String, work: Path, sourceBytes: Long, kind: AnimatedKind): Outcome {
        val alphaFile = work.resolve(AnimatedPlan.ALPHA_STATS)
        if (!Files.isRegularFile(alphaFile)) throw FfmpegFailedException("ffmpeg wrote no alpha statistics")
        val alpha = AnimatedPlan.parseAlpha(Files.readString(alphaFile))
        // 정지 이미지로만 풀렸다(움직이는 WebP 를 첫 장만 읽는 ffmpeg) - 움직이는 이미지로 등록된 것이니 한 장이면 못 푼 것이다
        if (alpha.frames < 2) return skipped(keyHash, "only ${alpha.frames} frame decoded")
        val (name, ext, contentType) = if (alpha.minAlpha < 255) Triple(AnimatedPlan.WEBP, "webp", "image/webp") else Triple(AnimatedPlan.MP4, "mp4", "video/mp4")
        // 변환본이 사본 파일 이름을 덮어쓰지 못한다(이미 투명한 움직이는 WebP 는 그 자체로 알맞다)
        if (ext == copyExt) return skipped(keyHash, "the result would replace the copy ($kind)")
        val result = work.resolve(name)
        val resultBytes = if (Files.isRegularFile(result)) Files.size(result) else 0L
        if (resultBytes <= 0L) throw FfmpegFailedException("ffmpeg wrote no $name")
        if (resultBytes >= sourceBytes) return skipped(keyHash, "$name $resultBytes bytes is not smaller than the source $sourceBytes")

        val key = ObjectKey("$folder$name")
        storage.upload(UploadObjectRequest(key, Files.readAllBytes(result), contentType, cacheControl = ImageVariantProcessor.CACHE_CONTROL))
        if (!displays.use(keyHash, ext)) {
            storage.delete(key)   // 그 사이 이미지가 지워졌다 - 올린 변환본이 고아가 되지 않게 치운다
            return skipped(keyHash, "image is gone")
        }
        return Outcome.Converted(ext)
    }

    /** ffmpeg 를 돌린다 - 정상 종료면 null, 아니면 로그 끝 한 줄. 시간 상한을 넘기면 프로세스를 죽이고 던진다 */
    private fun run(args: List<String>, work: Path): String? {
        val errors = work.resolve("ffmpeg.log")
        val process = try {
            ProcessBuilder(args).directory(work.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors.toFile()).start()
        } catch (e: java.io.IOException) {
            throw FfmpegFailedException("cannot start ffmpeg '${args.first()}'", e)
        }
        try {
            if (!process.waitFor(props.timeout.toMillis(), TimeUnit.MILLISECONDS)) throw FfmpegFailedException("ffmpeg timed out after ${props.timeout}")
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
        val exit = process.exitValue()
        // 신호로 죽은 것(OOM kill 137 · SIGTERM 143)은 파일 탓이 아니다 - 던져서 큐가 다시 한다.
        // 129~255 의 다른 값은 ffmpeg 자기 오류(AVERROR 하위 8비트 - 열기 실패 183 등)라 파일 탓으로 본다
        if (exit in KILLED_BY_SIGNAL) throw FfmpegFailedException("ffmpeg was killed by a signal (exit $exit)")
        return if (exit == 0) null else "exit $exit ${tail(errors.toFile())}"
    }

    private fun tail(file: File): String = if (file.isFile) file.readText().takeLast(300).trim() else ""

    private fun skipped(keyHash: String, reason: String): Outcome.Skipped {
        log.info("animated display conversion skipped {}: {}", keyHash.take(12), reason)
        return Outcome.Skipped(reason)
    }

    private companion object {
        const val HEAD_BYTES = 12L
        val RESULTS = listOf(AnimatedPlan.MP4 to "mp4", AnimatedPlan.WEBP to "webp")
        val KILLED_BY_SIGNAL = setOf(137, 143)
    }
}
