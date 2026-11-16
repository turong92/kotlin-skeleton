package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/**
 * 로컬 모드 — `video-worker local <입력 파일> <출력 폴더> [--compat]`. 서버 · 큐 · 토큰 · 저장소 없이 파일 하나를 판들로 만든다:
 * 출력 폴더에 원본 판 `o-<16>/`, 소리 판 `a-<16>/`(소리가 없으면 안 만든다), 포스터 `p-<16>.jpg`, 결과 요약 `manifest.json`(`--compat` 이면 `c-<16>/` 도).
 * 만드는 코드는 서버 모드와 같은 [Builds](같은 계획 · 같은 검증)이고 **올리기는 하지 않는다** — 폴더를 어디로 올릴지는 이 폴더를 읽는 쪽 몫이다
 */
class LocalMode(private val tools: Tools) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val builds = Builds(tools)
    private val json = JsonMapper.builder().build()

    /** 명령줄 인자(`local` 뒤) → 종료 코드. 0 = 끝, 1 = 못 만들었다, 2 = 사용법 */
    fun cli(args: List<String>): Int {
        val flags = args.filter { it.startsWith("--") }
        val paths = args.filter { !it.startsWith("--") }
        if (paths.size != 2 || flags.any { it != "--compat" }) {
            System.err.println("사용법: video-worker local <입력 파일> <출력 폴더> [--compat]")
            return 2
        }
        if (!Path.of(paths[0]).isRegularFile()) {
            System.err.println("입력 파일이 없다: ${paths[0]}")
            return 2
        }
        return try {
            run(Path.of(paths[0]).toAbsolutePath(), Path.of(paths[1]).toAbsolutePath(), compat = "--compat" in flags)
            0
        } catch (e: JobFailure) {
            System.err.println("실패: ${e.code} - ${e.detail}")
            1
        } catch (e: ToolFailure) {
            System.err.println("실패: ffmpeg 종료 ${e.exit} - ${e.tail.takeLast(300)}")
            1
        } catch (e: java.io.IOException) {
            System.err.println("실패: ${e.javaClass.simpleName} - ${e.message}")
            1
        } catch (e: RuntimeException) {
            // 예상 밖 오류도 스택트레이스 대신 한 줄과 종료 1 — 호출한 스크립트가 종료 코드만 본다
            System.err.println("실패: ${e.javaClass.simpleName} - ${e.message}")
            1
        }
    }

    /** [input] 하나를 [out] 폴더의 판들로. 실패하면 `manifest.json` 을 쓰지 않는다(판 폴더가 일부 남을 수는 있다) */
    fun run(input: Path, out: Path, compat: Boolean) {
        if (!input.isRegularFile()) throw JobFailure(VideoFail.CORRUPT, "입력 파일이 없다: $input")
        Files.createDirectories(out)
        // 같은 폴더로 다시 돌릴 때 실패해도 낡은 manifest 가 남아 완성본처럼 보이지 않게 시작하면서 지운다
        Files.deleteIfExists(out.resolve(MANIFEST))
        Files.deleteIfExists(out.resolve(LocalMaster.FILE))
        // 작업 폴더는 출력 밖(시스템 임시 폴더) — 강제 종료로 남아도 출력 폴더와 함께 올라가지 않는다
        val work = Files.createTempDirectory("video-worker-local-")
        val cancel = AtomicBoolean(false)
        val progress = Progress()
        try {
            val original = builds.remux(input, work, verifyDecode = true, jobId = "local", cancel, progress)
            // 조각만 읽은 ffprobe 는 소리 프로파일을 못 알려 줄 때가 있다 — 원본 소리는 AAC 면 복사(입력 파일의 프로파일), 아니면 일꾼이 AAC-LC 로 바꿔 만든 것이다
            val copiedProfile = if (original.source.audioCodec == "aac") original.source.audioProfile else LC
            val tier = original.tier.withAudioProfile(copiedProfile)
            place(tier, out)
            val playlist = out.resolve(tier.folder).resolve(HlsNames.PLAYLIST).toString()
            val audio = if (tier.info.hasAudio) builds.audio(playlist, null, work, cancel, progress).withAudioProfile(copiedProfile).also { place(it, out) } else null
            val compatTier = if (compat) builds.compat(playlist, null, work, cancel, progress).withAudioProfile(LC).also { place(it, out) } else null
            Files.copy(original.poster, out.resolve(original.posterName), StandardCopyOption.REPLACE_EXISTING)

            val manifest = linkedMapOf<String, Any?>(
                "version" to 1,
                "hasAudio" to tier.info.hasAudio,
                "original" to entry(tier),
            )
            if (audio != null) manifest["audio"] = entry(audio)
            if (compatTier != null) manifest["compat"] = entry(compatTier)
            // 마스터를 만드는 조건은 LocalMaster.text 에 — 못 만들면 파일도 manifest 항목도 없다
            val master = compatTier?.let { LocalMaster.text(tier, it) }
            if (master != null) {
                Files.writeString(out.resolve(LocalMaster.FILE), master)
                manifest["master"] = mapOf("path" to LocalMaster.FILE)
            }
            manifest["poster"] = mapOf("path" to original.posterName, "width" to original.posterSize.first, "height" to original.posterSize.second, "bytes" to original.poster.fileSize())
            // 임시 이름으로 다 쓴 뒤 바꿔치기 — 반쯤 쓰인 manifest 가 보이지 않는다
            val tmp = out.resolve("$MANIFEST.tmp")
            Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n")
            Files.move(tmp, out.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            log.info("끝 {} → {}", input.fileName, out)
        } finally {
            runCatching { work.toFile().deleteRecursively() }
        }
    }

    private companion object {
        const val MANIFEST = "manifest.json"
        const val LC = "LC"
    }

    /** 만든 판을 읽은 결과에 소리 프로파일이 없으면 [profile] 로 채운다 — 있으면 ffprobe 가 읽은 값을 믿는다 */
    private fun BuiltTier.withAudioProfile(profile: String?): BuiltTier =
        if (info.audioProfile != null) this else BuiltTier(folder, files, sha, info.copy(audioProfile = profile))

    /** 만든 폴더를 출력 폴더로 — 이미 같은 이름(= 같은 내용 해시)이 있으면 덮어쓴다 */
    private fun place(tier: BuiltTier, out: Path) {
        val target = Files.createDirectories(out.resolve(tier.folder))
        for (file in tier.files) Files.copy(file, target.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
    }

    /** 영상 줄이 없는 판(소리)은 가로 · 세로 · 영상 코덱을 싣지 않는다 */
    private fun entry(tier: BuiltTier): Map<String, Any?> {
        val info = tier.info
        val hasVideo = info.videoStreams > 0
        return linkedMapOf(
            "path" to "${tier.folder}/${HlsNames.PLAYLIST}",
            "durationMs" to info.durationMs,
            "width" to if (hasVideo) info.displayWidth else null,
            "height" to if (hasVideo) info.displayHeight else null,
            "bytes" to tier.bytes,
            "videoCodec" to if (hasVideo) info.videoCodec else null,
            "audioCodec" to info.audioCodec,
            "codec" to LocalMaster.codec(info),
        )
    }
}
