package dev.sumin.skeleton.video

/**
 * 일꾼이 올릴 수 있는 이름과 형식 — 허용 경로 패턴으로만 presign 한다.
 * 키는 서버가 영상 폴더(`…/<영상 id>/`) 아래에 붙이므로 여기서 받는 것은 **그 아래 이름**이고, 상위 폴더로 나가는 길은 모양에서 막힌다.
 * 순수 함수 — 저장소 대조(폴더 나열 · 합계 · 해시)는 [VideoJobs] 가 한다
 */
object VideoResultVerifier {
    /** 조각 폴더 안 이름 — 변형 목록 · init · 조각. 한 덩어리 `*.mp4` 는 `init.mp4` 만 허용된다 */
    private const val FOLDER_FILE = "(index\\.m3u8|init\\.mp4|s[0-9]{5}\\.m4s)"
    private val ORIG = Regex("^o-[0-9a-f]{16}/$FOLDER_FILE$")
    private val ORIG_PLAYLIST = Regex("^o-[0-9a-f]{16}/index\\.m3u8$")
    private val POSTER = Regex("^p-[0-9a-f]{16}\\.jpg$")
    private val COMPAT = Regex("^c-[0-9a-f]{16}/$FOLDER_FILE$")
    private val COMPAT_PLAYLIST = Regex("^c-[0-9a-f]{16}/index\\.m3u8$")
    private val HLS_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.(m3u8|m4s|mp4)$")
    private val BUILD = Regex("^[0-9a-f]{32}$")
    private val DISPLAY = Regex("^display\\.(mp4|webp)$")

    /** [build] 는 사다리 차선에서 서버가 정해 준 폴더 id */
    fun allowedName(lane: VideoLane, build: String?, name: String): Boolean = when (lane) {
        VideoLane.REMUX -> ORIG.matches(name) || POSTER.matches(name)
        VideoLane.COMPAT -> COMPAT.matches(name)
        VideoLane.LADDER -> build != null && BUILD.matches(build) && name.startsWith("h-$build/") && HLS_FILE.matches(name.removePrefix("h-$build/"))
        VideoLane.ANIMATED -> DISPLAY.matches(name)
    }

    fun contentTypeOf(name: String): String? = when (name.substringAfterLast('.', "")) {
        "mp4", "m4s" -> "video/mp4"
        "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        "m3u8" -> "application/vnd.apple.mpegurl"
        else -> null
    }

    /** `complete` 가 보고하는 결과 이름 — 원본 단 · 호환 단은 폴더의 변형 목록(`o-<16>/index.m3u8` · `c-<16>/index.m3u8`) */
    fun isOriginalPlaylist(name: String): Boolean = ORIG_PLAYLIST.matches(name)

    fun isCompatPlaylist(name: String): Boolean = COMPAT_PLAYLIST.matches(name)

    /** 폴더 이름의 해시(`o-<16>/…`)는 일꾼이 보고한 폴더 내용 SHA-256 의 앞 16자다 — 키가 곧 내용이다 */
    fun hashMatches(name: String, sha256: String): Boolean =
        ORIG_PLAYLIST.matches(name) && sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' } && name.substring(2, 18) == sha256.substring(0, 16)

    /** 결과 이름이 속한 폴더(`o-<16>/` · `c-<16>/`) — 접두사 */
    fun folderOf(name: String): String = name.substringBefore('/') + "/"

    fun isBuild(value: String?): Boolean = value != null && BUILD.matches(value)
}
