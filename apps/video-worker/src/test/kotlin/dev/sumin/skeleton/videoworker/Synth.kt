package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 시험 영상 합성 — ffmpeg lavfi(`testsrc2` · `sine`). 실제 인물 영상은 쓰지 않는다 */
object Synth {
    val available: Boolean by lazy {
        runCatching {
            listOf("ffmpeg", "ffprobe").all { ProcessBuilder(it, "-version").redirectErrorStream(true).start().let { p -> p.inputStream.readAllBytes(); p.waitFor() == 0 } }
        }.getOrDefault(false)
    }

    fun ffmpeg(vararg args: String) {
        val p = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-y") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(p.waitFor(3, TimeUnit.MINUTES) && p.exitValue() == 0) { "ffmpeg 합성 실패: $out" }
    }

    private fun src(w: Int, h: Int, sec: Int) = arrayOf("-f", "lavfi", "-i", "testsrc2=size=${w}x$h:rate=30:duration=$sec")
    private fun tone(sec: Int) = arrayOf("-f", "lavfi", "-i", "sine=frequency=440:duration=$sec")

    fun h264(dir: Path, name: String = "h264.mp4", w: Int = 1920, h: Int = 1080, sec: Int = 3, audio: Boolean = true): Path {
        val out = dir.resolve(name)
        ffmpeg(*src(w, h, sec), *(if (audio) tone(sec) else emptyArray()), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            *(if (audio) arrayOf("-c:a", "aac", "-shortest") else emptyArray()), out.toString())
        return out
    }

    /** HEVC 4K — `hev1` 표지로 만들어 hvc1 로 고쳐 싸는지 본다 */
    fun hevc4k(dir: Path, name: String = "hevc4k.mp4", sec: Int = 1): Path {
        val out = dir.resolve(name)
        ffmpeg(*src(3840, 2160, sec), *tone(sec), "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error", "-pix_fmt", "yuv420p",
            "-tag:v", "hev1", "-c:a", "aac", "-shortest", out.toString())
        return out
    }

    /** PCM 오디오 + 위치 태그가 든 MOV */
    fun pcmMovWithLocation(dir: Path, name: String = "pcm.mov"): Path {
        val out = dir.resolve(name)
        ffmpeg(*src(1280, 720, 2), *tone(2), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "pcm_s16le", "-shortest",
            "-movflags", "use_metadata_tags", "-metadata", "com.apple.quicktime.location.ISO6709=+37.5665+126.9780/",
            "-metadata", "com.apple.quicktime.make=TestPhone", out.toString())
        return out
    }

    /** 표시 회전 90 도(display matrix)가 든 MOV — 폰 세로 영상 */
    fun rotated(dir: Path, base: Path, name: String = "rot.mov"): Path {
        val out = dir.resolve(name)
        ffmpeg("-display_rotation", "90", "-i", base.toString(), "-c", "copy", out.toString())
        return out
    }

    /** 가변 프레임율(VFR) — 프레임을 불규칙하게 솎아(10프레임 중 앞 5장만) 시간 간격이 들쭉날쭉하다 */
    fun vfr(dir: Path, name: String = "vfr.mp4", sec: Int = 3): Path {
        val out = dir.resolve(name)
        ffmpeg(*src(1280, 720, sec), *tone(sec), "-vf", "select='lt(mod(n,10),5)',setpts=N/FRAME_RATE/TB*1+0*PTS", "-c:v", "libx264", "-preset", "ultrafast",
            "-pix_fmt", "yuv420p", "-fps_mode", "vfr", "-c:a", "aac", "-shortest", out.toString())
        return out
    }

    /** 조각난 MP4(fMP4) — moov 가 비어 있고 moof 조각이 이어진다(스트리밍 녹화물 모양) */
    fun fragmented(dir: Path, name: String = "frag.mp4", sec: Int = 3): Path {
        val out = dir.resolve(name)
        ffmpeg(*src(1280, 720, sec), *tone(sec), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
            "-movflags", "frag_keyframe+empty_moov+default_base_moof", out.toString())
        return out
    }

    /** 원본 단 모양 조각 폴더 — [source] 를 복사해 fMP4 HLS 로(공개된 `o-<해시>/` 흉내). 폴더를 돌려준다 */
    fun hlsFolder(dir: Path, source: Path, name: String = "o-0123456789abcdef"): Path {
        val out = Files.createDirectories(dir.resolve(name))
        val p = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-y", "-i", source.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-tag:v", "hvc1",
            "-f", "hls", "-hls_time", "6", "-hls_list_size", "0", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4", "-hls_flags", "independent_segments",
            "-hls_fmp4_init_filename", "init.mp4", "-hls_segment_filename", "s%05d.m4s", "index.m3u8",
        ).directory(out.toFile()).redirectErrorStream(true).start()
        val log = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(p.waitFor(3, TimeUnit.MINUTES) && p.exitValue() == 0) { "조각 합성 실패: $log" }
        return out
    }

    /** 표지 그림으로 쓸 정사각 JPEG — 영상 크기와 달라서 포스터가 이 그림에서 왔는지 크기로 가린다 */
    fun coverJpeg(dir: Path, name: String = "cover.jpg", size: Int = 96): Path {
        val out = dir.resolve(name)
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=${size}x$size:duration=1", "-frames:v", "1", out.toString())
        return out
    }

    /** 표지 그림(attached picture)만 더한 MP4 — 영상 320x240 */
    fun withCover(dir: Path, cover: Path, name: String = "cov.mp4"): Path {
        val out = dir.resolve(name)
        ffmpeg(*src(320, 240, 2), *tone(2), "-i", cover.toString(), "-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-c:v:1", "copy", "-disposition:v:1", "attached_pic", "-shortest", out.toString())
        return out
    }

    /**
     * 편집 앱이 내보낸 모양 — 타임코드 트랙 · 표지 그림 · 가변 프레임율 · 소프트 자막 · 챕터가 한 파일에 든 MP4
     * (정상 파일이다: 거절하지 않고 영상 · 소리만 싣는다)
     */
    fun editorStyle(dir: Path, cover: Path, name: String = "editor.mp4", sec: Int = 3): Path {
        val srt = dir.resolve("$name.srt").also { Files.writeString(it, "1\n00:00:00,000 --> 00:00:01,000\nhello\n") }
        val meta = dir.resolve("$name.ffmeta").also { Files.writeString(it, ";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=one\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=1000\nEND=${sec * 1000}\ntitle=two\n") }
        val out = dir.resolve(name)
        ffmpeg(*src(320, 240, sec), *tone(sec), "-i", cover.toString(), "-i", srt.toString(), "-f", "ffmetadata", "-i", meta.toString(),
            "-map", "0:v", "-map", "1:a", "-map", "2:v", "-map", "3:s", "-map_chapters", "4",
            "-c:v:0", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-filter:v:0", "select='lt(mod(n,10),5)',setpts=N/FRAME_RATE/TB", "-fps_mode:v:0", "vfr",
            "-c:v:1", "copy", "-disposition:v:1", "attached_pic", "-c:a", "aac", "-c:s", "mov_text", "-timecode", "01:00:00:00", "-shortest", out.toString())
        return out
    }

    /** [base] 의 영상 데이터(mdat) 한가운데 바이트를 망가뜨린 사본 — 머리(moov)는 멀쩡해 프로브는 읽히지만 디코드는 실패한다 */
    fun damaged(dir: Path, base: Path, name: String = "damaged.mp4"): Path {
        val bytes = Files.readAllBytes(base)
        val mdat = String(bytes, Charsets.ISO_8859_1).indexOf("mdat")
        check(mdat > 0) { "mdat 이 없다" }
        val from = mdat + 4 + (bytes.size - mdat) / 4
        val to = minOf(bytes.size - 1, from + (bytes.size - mdat) / 2)
        for (i in from..to) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
        return dir.resolve(name).also { Files.write(it, bytes) }
    }

    fun webm(dir: Path, name: String = "x.webm"): Path {
        val out = dir.resolve(name)
        ffmpeg(*src(320, 240, 1), "-c:v", "libvpx", "-b:v", "200k", out.toString())
        return out
    }

    fun tempDir(): Path = Files.createTempDirectory("video-worker-test-")
}
