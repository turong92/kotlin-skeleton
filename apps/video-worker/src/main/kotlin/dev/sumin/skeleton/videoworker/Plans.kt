package dev.sumin.skeleton.videoworker

/** 사다리 한 판 — 이름은 짧은 변 높이 */
data class Rung(val height: Int, val videoKbps: Int) {
    val name: String get() = height.toString()
    val bandwidth: Long get() = (videoKbps + 128) * 1000L
}

private const val ISOLATED = "file,pipe"
private const val NETWORK = "file,http,https,tcp,tls,crypto"

/**
 * 믿지 못하는 올린 파일을 읽는 길의 허용 목록 — ffmpeg 6.1.1 에 고쳐진 패키지가 없는 CVE-2025-59734(SANM/Smush 디코더) ·
 * CVE-2024-32228(디코드 경로 오버플로) 완화. 목록 밖 컨테이너 · 코덱은 demuxer · decoder 가 아예 열리지 않는다(`-format_whitelist` · `-codec_whitelist`).
 * 늘릴 때는 여기와 [Probe] 의 거절 판정을 같이 고친다(docs/operations/video.md 「읽는 형식 허용 목록」)
 */
object Allowed {
    /** ffmpeg 의 mov demuxer 가 한 묶음으로 부르는 이름 — 편집기 안내 「MP4 · MOV」 */
    const val UPLOAD_FORMATS = "mov,mp4,m4a,3gp,3g2,mj2"
    val VIDEO_CODECS = listOf("h264", "hevc")
    val AUDIO_CODECS = listOf(
        "aac", "mp3", "alac", "ac3", "eac3", "opus",
        "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_s32be", "pcm_f32le", "pcm_f32be", "pcm_u8", "pcm_s8",
    )

    /**
     * 표지 그림(attached_pic) 트랙 코덱 — ffprobe 는 파일 안 모든 스트림의 디코더를 열어 스트림을 골라 버릴 수 없어(`-select_streams` 도 같다) 목록에 둔다.
     * 주 영상으로는 받지 않는다: [Probe] 는 표지 그림을 뺀 첫 영상 스트림이 h264 · hevc 인지만 보고, 리먹스 · 포스터는 `0:V:0`(표지 제외)만 고른다
     */
    val COVER_CODECS = listOf("mjpeg", "png")

    /**
     * 소프트 자막(MOV 의 tx3g) — 디코더가 있는 트랙이라 목록에 없으면 ffprobe 가 파일 전체를 열지 못한다. 자막은 조각에 안 싣지만 있다는 이유로 거절하지 않는다.
     * 타임코드 · 메타데이터 같은 데이터 트랙은 디코더가 없어 목록과 상관없이 통과한다
     */
    val SUBTITLE_CODECS = listOf("mov_text")
    val UPLOAD_CODECS: String = (VIDEO_CODECS + AUDIO_CODECS + COVER_CODECS + SUBTITLE_CODECS).joinToString(",")

    /** 우리가 만든 원본 단 · 호환 단 · 사다리를 다시 읽는 길 — 영상은 같고 소리는 늘 AAC */
    val BUILT_CODECS: String = (VIDEO_CODECS + "aac").joinToString(",")

    /** 우리가 만든 포스터 JPEG */
    const val POSTER_CODECS = "mjpeg"
}

private fun isUrl(input: String) = input.startsWith("http://") || input.startsWith("https://")

/** 입력 앞 공통 인자 — 격리(`-protocol_whitelist`) · 입력 형식 강제 · 로그 줄임. [headers] 는 로컬 compose 의 Host 되돌림 한 줄 */
private fun inputArgs(input: String, headers: String?, format: String = "mov"): List<String> = buildList {
    add("-hide_banner"); add("-nostdin"); add("-v"); add("error")
    add("-protocol_whitelist"); add(if (isUrl(input)) NETWORK else ISOLATED)
    if (headers != null && isUrl(input)) { add("-headers"); add(headers) }
    if (format == "mov") { add("-format_whitelist"); add(Allowed.UPLOAD_FORMATS) }
    add("-codec_whitelist"); add(if (format == "mov") Allowed.UPLOAD_CODECS else Allowed.BUILT_CODECS)
    add("-f"); add(format) // 올린 파일은 mp4 · mov 계열만, 원본 단 읽기는 hls 만 — 확장자 · Content-Type 을 안 믿는다
    add("-i"); add(input)
}

/** 조각 폴더 파일 이름 — 원본 단(`o-<해시>/`) · 호환 단(`c-<해시>/`)이 같은 모양이고 서버 허용 패턴(`VideoResultVerifier`)과 같다 */
object HlsNames {
    const val PLAYLIST = "index.m3u8"
    const val INIT = "init.mp4"
    const val SEGMENT = "s%05d.m4s"
}

/** 조각 내기 공통 출력 인자 — 6초 목표, 키프레임에서만 자른다(원본 단은 복사라 키프레임 간격이 곧 조각 길이) */
private fun hlsOutput(): List<String> = listOf(
    "-f", "hls", "-hls_time", "6", "-hls_list_size", "0", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
    "-hls_flags", "independent_segments", "-hls_fmp4_init_filename", HlsNames.INIT, "-hls_segment_filename", HlsNames.SEGMENT, HlsNames.PLAYLIST,
)

private const val SDR_CHAIN = "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p"

/**
 * 올린 파일 → 원본 단: 재인코딩 없이 **fMP4 HLS 조각**으로 낸다 — 공개 버킷에 영상 파일 하나가 없다.
 * 작업 폴더(`o`)에서 돌린다(상대 이름). 메타데이터 · 위치 제거, HEVC 는 hvc1, AAC 가 아닌 오디오(PCM · MP3 …)만 AAC —
 * Apple HLS 규격의 fMP4 오디오 목록에 MP3 가 없다. 회전 표지(display matrix)는 그대로 둔다(세로 영상 결론: 설계 1.4)
 */
object RemuxPlan {
    fun args(input: String, info: MediaInfo): List<String> = buildList {
        add("ffmpeg")
        addAll(inputArgs(input, null))
        add("-map"); add("0:V:0")
        add("-map"); add("0:a:0?")
        add("-map_metadata"); add("-1")
        add("-map_metadata:s:v"); add("-1")
        add("-map_metadata:s:a"); add("-1")
        add("-map_chapters"); add("-1")
        val transcodeAudio = info.audioCodec != null && info.audioCodec != "aac"
        if (transcodeAudio) {
            add("-c:v"); add("copy"); add("-c:a"); add("aac"); add("-b:a"); add("192k")
        } else {
            add("-c"); add("copy")
        }
        if (info.needsHvc1Tag) { add("-tag:v"); add("hvc1") }
        add("-fflags"); add("+bitexact")
        add("-flags:v"); add("+bitexact")
        add("-flags:a"); add("+bitexact")
        addAll(hlsOutput())
    }
}

/** 원본 단 → 호환 단: H.264 High 720p(짧은 변) 2.8Mbps · AAC 128k, 6초 fMP4 HLS 한 판. 작업 폴더에서 돌린다 */
object CompatPlan {
    fun args(input: String, info: MediaInfo, headers: String?): List<String> = buildList {
        add("ffmpeg")
        addAll(inputArgs(input, headers, "hls"))
        add("-map"); add("0:v:0")
        add("-map"); add("0:a:0?")
        add("-map_metadata"); add("-1")
        add("-map_chapters"); add("-1")
        add("-vf"); add(listOfNotNull(if (info.hdr) SDR_CHAIN else null, shortSideScale(720), if (info.hdr) null else "format=yuv420p").joinToString(","))
        add("-c:v"); add("libx264"); add("-profile:v"); add("high"); add("-preset"); add("veryfast")
        add("-b:v"); add("2800k"); add("-maxrate"); add("2800k"); add("-bufsize"); add("5600k")
        add("-pix_fmt"); add("yuv420p")
        add("-force_key_frames"); add("expr:gte(t,n_forced*6)"); add("-sc_threshold"); add("0")
        add("-c:a"); add("aac"); add("-b:a"); add("128k")
        add("-fflags"); add("+bitexact")
        addAll(hlsOutput())
    }
}

/** 원본 단 → HLS 아랫단: 원본의 짧은 변보다 작은 판만, 6초 fMP4 조각, 마스터 목록. 작업 폴더에서 돌린다(상대 이름) */
object LadderPlan {
    private val ALL = listOf(Rung(1080, 5000), Rung(720, 2800), Rung(480, 1200))

    fun rungs(info: MediaInfo): List<Rung> = ALL.filter { it.height < info.shortSide }

    fun args(input: String, info: MediaInfo, headers: String?): List<String> {
        val rungs = rungs(info)
        require(rungs.isNotEmpty()) { "만들 판이 없다" }
        val n = rungs.size
        val labels = rungs.indices.joinToString("") { "[s$it]" }
        val filter = buildString {
            append("[0:v:0]")
            if (info.hdr) append(SDR_CHAIN).append(',')
            append("format=yuv420p,split=").append(n).append(labels).append(';')
            rungs.forEachIndexed { i, r -> append("[s$i]").append(shortSideScale(r.height)).append("[v$i]"); if (i < n - 1) append(';') }
        }
        return buildList {
            add("ffmpeg")
            addAll(inputArgs(input, headers, "hls"))
            add("-filter_complex"); add(filter)
            rungs.indices.forEach { i ->
                add("-map"); add("[v$i]")
            }
            if (info.hasAudio) rungs.indices.forEach { _ -> add("-map"); add("0:a:0") }
            add("-map_metadata"); add("-1")
            add("-c:v"); add("libx264"); add("-profile:v"); add("high"); add("-preset"); add("veryfast"); add("-pix_fmt"); add("yuv420p")
            rungs.forEachIndexed { i, r ->
                add("-b:v:$i"); add("${r.videoKbps}k"); add("-maxrate:v:$i"); add("${r.videoKbps}k"); add("-bufsize:v:$i"); add("${r.videoKbps * 2}k")
            }
            add("-force_key_frames"); add("expr:gte(t,n_forced*6)"); add("-sc_threshold"); add("0")
            if (info.hasAudio) { add("-c:a"); add("aac"); add("-b:a"); add("128k") }
            add("-f"); add("hls")
            add("-hls_time"); add("6")
            add("-hls_playlist_type"); add("vod")
            add("-hls_segment_type"); add("fmp4")
            add("-hls_flags"); add("independent_segments")
            // 판이 하나면 ffmpeg 가 init 이름의 %v 를 풀지 않는다(조각 · 목록 이름만 푼다)
            add("-hls_fmp4_init_filename"); add(if (n == 1) "init_0.mp4" else "init_%v.mp4")
            add("-hls_segment_filename"); add("v%v_%03d.m4s")
            add("-master_pl_name"); add("master.m3u8")
            add("-var_stream_map"); add(rungs.indices.joinToString(" ") { if (info.hasAudio) "v:$it,a:$it" else "v:$it" })
            add("v%v.m3u8")
        }
    }
}

/** 올린 파일을 끝까지 디코드해 본다(공개 영상의 정상 여부) — 쓰는 두 스트림만, 디코드 오류가 나면 바로 멈춘다. 경고(타임스탬프 등)는 오류가 아니다 */
object DecodeCheckPlan {
    fun args(input: String): List<String> = buildList {
        add("ffmpeg")
        addAll(inputArgs(input, null))
        add("-map"); add("0:V:0")
        add("-map"); add("0:a:0?")
        add("-xerror")
        add("-f"); add("null"); add("-")
    }
}

/**
 * 포스터 JPEG (긴 변 1280 이하). 파일에 표지 그림(attached picture)이 있으면 [coverStream] 으로 그 스트림 한 장을, 없으면 영상 한 장면을 뽑는다.
 * 회전은 ffmpeg 의 자동 회전이 구운 채로 뽑는다
 */
object PosterPlan {
    fun args(input: String, output: String, durationMs: Long, coverStream: Int? = null): List<String> = buildList {
        add("ffmpeg")
        add("-hide_banner"); add("-nostdin"); add("-v"); add("error")
        add("-protocol_whitelist"); add(if (isUrl(input)) NETWORK else ISOLATED)
        if (coverStream == null) { add("-ss"); add(if (durationMs > 1500) "1.000" else "0.000") }
        add("-format_whitelist"); add(Allowed.UPLOAD_FORMATS)
        add("-codec_whitelist"); add(Allowed.UPLOAD_CODECS)
        add("-f"); add("mov")
        add("-i"); add(input)
        add("-map"); add(if (coverStream != null) "0:$coverStream" else "0:V:0")
        add("-frames:v"); add("1")
        add("-vf"); add("scale=w='if(gt(iw,ih),min(1280,iw),-2)':h='if(gt(iw,ih),-2,min(1280,ih))'")
        add("-q:v"); add("3")
        add("-y"); add(output)
    }
}

/** 짧은 변이 [target] 이 되게 — 이미 그보다 작으면 키우지 않는다. 짝수로 맞춘다(`-2`) */
private fun shortSideScale(target: Int): String =
    "scale=w='if(gt(iw,ih),-2,min($target,iw))':h='if(gt(iw,ih),min($target,ih),-2)'"

/** 움직이는 이미지 입력 - 서버가 매직 바이트로 이미 가려 보냈지만 일꾼도 첫 바이트로 다시 본다(확장자 · 내용 형식을 안 믿는다) */
enum class AnimatedKind(val format: String, val codec: String) {
    GIF("gif", "gif"),
    WEBP("webp_pipe", "webp");

    companion object {
        fun sniff(head: ByteArray): AnimatedKind? = when {
            head.size >= 6 && String(head, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" } -> GIF
            head.size >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> WEBP
            else -> null
        }
    }
}

/**
 * 움직이는 GIF · WebP → **한 번의 디코드**에서 세 갈래: ① 소리 없는 H.264 MP4 ② 움직이는 WebP(투명 유지) ③ 알파 최솟값 기록(`alpha.txt`).
 * 쓰는 것은 ffmpeg 가 앞 장면 위에 겹쳐 합성한 실제 화면(RGBA)의 알파 - 파일의 투명 표시(GIF 의 투명 색)는 「앞 장면과 같은 부분」 최적화로도 켜져 믿을 수 없다.
 * 알파 최솟값이 255 미만이면 투명이라 WebP 를, 아니면 MP4 를 쓰고 안 쓰는 쪽은 버린다(디코드는 다시 하지 않는다).
 * 프레임 시간은 그대로(`passthrough`), 크기는 그대로(H.264 가 짝수만 받아 홀수 크기는 마지막 한 줄만 잘라낸다), 색은 BT.709 로 표시해 브라우저가 색을 틀리지 않게 한다.
 * 작업 폴더에서 돌린다(상대 이름). 입력 허용 목록은 이 차선만 gif · webp 를 연다
 */
object AnimatedPlan {
    const val MP4 = "display.mp4"
    const val WEBP = "display.webp"
    const val ALPHA_STATS = "alpha.txt"

    fun args(input: String, kind: AnimatedKind): List<String> = buildList {
        add("ffmpeg")
        add("-hide_banner"); add("-nostdin"); add("-v"); add("error")
        add("-protocol_whitelist"); add(ISOLATED)
        add("-format_whitelist"); add(kind.format)
        add("-codec_whitelist"); add(kind.codec)
        add("-f"); add(kind.format)
        add("-i"); add(input)
        add("-filter_complex"); add(
            "[0:v:0]format=rgba,split=3[s1][s2][s3];" +
                "[s1]alphaextract,signalstats,metadata=mode=print:key=lavfi.signalstats.YMIN:file=$ALPHA_STATS[alpha];" +
                "[s2]crop=trunc(iw/2)*2:trunc(ih/2)*2,scale=out_color_matrix=bt709:out_range=tv,format=yuv420p[mp4v];" +
                "[s3]null[webpv]",
        )
        add("-fps_mode"); add("passthrough")
        add("-map_metadata"); add("-1")
        add("-an")
        add("-map"); add("[alpha]"); add("-f"); add("null"); add("-")
        add("-map"); add("[mp4v]"); add("-c:v"); add("libx264"); add("-crf"); add("14"); add("-preset"); add("slow"); add("-pix_fmt"); add("yuv420p")
        add("-colorspace"); add("bt709"); add("-color_primaries"); add("bt709"); add("-color_trc"); add("bt709")
        add("-fflags"); add("+bitexact"); add("-flags:v"); add("+bitexact"); add("-movflags"); add("+faststart"); add(MP4)
        add("-map"); add("[webpv]"); add("-c:v"); add("libwebp_anim"); add("-lossless"); add("1"); add("-loop"); add("0"); add(WEBP)
    }

    /** `alpha.txt` - 프레임마다 한 줄 `lavfi.signalstats.YMIN=<0..255>`. 프레임 수와 알파 최솟값 */
    data class AlphaStats(val frames: Int, val minAlpha: Int)

    fun parseAlpha(text: String): AlphaStats {
        val values = Regex("lavfi\\.signalstats\\.YMIN=(\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()
        return AlphaStats(values.size, values.minOrNull() ?: 255)
    }
}
