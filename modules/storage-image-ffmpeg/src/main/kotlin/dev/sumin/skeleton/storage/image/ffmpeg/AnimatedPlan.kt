package dev.sumin.skeleton.storage.image.ffmpeg

/** 움직이는 이미지 입력 - 파일 머리 바이트로 가린다(확장자 · 올린 쪽이 말한 Content-Type 을 믿지 않는다) */
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
 * 입력은 로컬 파일 하나뿐이다(`-protocol_whitelist file`) - 형식 · 코덱 허용 목록은 gif · webp 만. 두 출력은 원본 크기에서 쓰기를 멈춘다(`-fs`) - 그만큼이면 원본보다 작지 않아 버려진다.
 * 작업 폴더에서 돌린다(상대 이름).
 */
object AnimatedPlan {
    const val MP4 = "display.mp4"
    const val WEBP = "display.webp"
    const val ALPHA_STATS = "alpha.txt"

    fun args(ffmpeg: String, input: String, kind: AnimatedKind, props: FfmpegProperties, maxOutputBytes: Long): List<String> = buildList {
        add(ffmpeg)
        add("-hide_banner"); add("-nostdin"); add("-v"); add("error")
        add("-protocol_whitelist"); add("file")
        add("-format_whitelist"); add(kind.format)
        add("-codec_whitelist"); add(kind.codec)
        add("-threads"); add(props.threads.toString())
        add("-f"); add(kind.format)
        add("-i"); add(input)
        add("-filter_threads"); add(props.threads.toString()); add("-filter_complex_threads"); add(props.threads.toString())
        add("-filter_complex"); add(
            "[0:v:0]format=rgba,split=3[s1][s2][s3];" +
                "[s1]alphaextract,signalstats,metadata=mode=print:key=lavfi.signalstats.YMIN:file=$ALPHA_STATS[alpha];" +
                "[s2]crop=trunc(iw/2)*2:trunc(ih/2)*2,scale=out_color_matrix=bt709:out_range=tv,format=${props.pixelFormat}[mp4v];" +
                "[s3]null[webpv]",
        )
        // 출력 옵션은 바로 뒤 출력 파일 하나에만 걸린다 - 메타데이터 제거(원본의 GIF 주석 등) · 프레임 시간 유지는 출력마다 적는다
        add("-map"); add("[alpha]"); add("-an"); add("-map_metadata"); add("-1"); add("-fps_mode"); add("passthrough"); add("-f"); add("null"); add("-")
        add("-map"); add("[mp4v]"); add("-an"); add("-map_metadata"); add("-1"); add("-fps_mode"); add("passthrough"); add("-threads"); add(props.threads.toString())
        add("-c:v"); add("libx264"); add("-crf"); add(props.crf.toString()); add("-preset"); add(props.preset); add("-pix_fmt"); add(props.pixelFormat)
        add("-colorspace"); add("bt709"); add("-color_primaries"); add("bt709"); add("-color_trc"); add("bt709")
        add("-fflags"); add("+bitexact"); add("-flags:v"); add("+bitexact"); add("-movflags"); add("+faststart")
        add("-fs"); add(maxOutputBytes.toString()); add(MP4)
        add("-map"); add("[webpv]"); add("-an"); add("-map_metadata"); add("-1"); add("-fps_mode"); add("passthrough"); add("-threads"); add(props.threads.toString())
        add("-c:v"); add("libwebp_anim"); add("-lossless"); add("1"); add("-loop"); add("0")
        add("-fs"); add(maxOutputBytes.toString()); add(WEBP)
    }

    /** `alpha.txt` - 프레임마다 한 줄 `lavfi.signalstats.YMIN=<0..255>`. 프레임 수와 알파 최솟값 */
    data class AlphaStats(val frames: Int, val minAlpha: Int)

    fun parseAlpha(text: String): AlphaStats {
        val values = Regex("lavfi\\.signalstats\\.YMIN=(\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()
        return AlphaStats(values.size, values.minOrNull() ?: 255)
    }
}
