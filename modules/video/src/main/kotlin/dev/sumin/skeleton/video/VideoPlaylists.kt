package dev.sumin.skeleton.video

import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedStorage
import java.time.Duration

/**
 * 비공개 영상의 재생 목록을 내준다 — 출입증이 맞고 그 영상이 가진 목록일 때만, 저장된 목록의 `init` · 조각 줄을 **영상 폴더 안의 허용 이름일 때만**
 * 출입증 남은 수명의 서명 주소로 바꿔 쓴다([VideoResultVerifier.allowedName] 이 일꾼의 올리기와 같은 이름 규칙을 쥐고 있다).
 * 다른 목록을 가리키는 줄(마스터의 `v0.m3u8` · `../o-…/index.m3u8`)은 그대로 둬서 같은 출입증 경로 아래로 풀린다. 폴더 밖 줄(`..` · 절대 주소 · 다른 폴더 · 질의)은 서명하지 않는다.
 * 위조 · 만료 · 모르는 영상 · 남의 이름은 모두 같은 [VideoNotFoundException] 이고, 그 전에는 저장소를 읽지도 서명하지도 않는다
 */
class VideoPlaylists(
    private val tokens: VideoPlaybackTokens,
    private val store: VideoStore,
    private val keys: VideoKeys,
    private val storage: PresignedStorage,
) {
    fun playlist(token: String, name: String): String {
        val verified = tokens.verify(token) ?: throw VideoNotFoundException()
        val video = store.find(verified.videoId)?.takeIf { it.visibility == VideoVisibility.PRIVATE && it.status == VideoStatus.PLAYABLE } ?: throw VideoNotFoundException()
        if (!isOwnPlaylist(video, name)) throw VideoNotFoundException()
        val folder = keys.folder(video)
        val text = storage.read(ObjectKey(folder + name)).use { it.readNBytes(MAX_PLAYLIST_BYTES + 1) }
        check(text.size <= MAX_PLAYLIST_BYTES) { "playlist of video ${video.id} is unreasonably large" }
        val ttl = maxOf(verified.remaining, MIN_SIGN)
        val dir = name.substringBeforeLast('/') + "/"
        fun sign(ref: String): String? {
            if (!PLAIN_FILE.matches(ref) || ref.endsWith(".m3u8") || !allowed(video, dir + ref)) return null
            return storage.presignDownload(PresignedDownloadRequest(ObjectKey(folder + dir + ref), expiresIn = ttl)).url.toString()
        }
        return String(text, Charsets.UTF_8).lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> URI_ATTRIBUTE.replace(line) { m -> "URI=\"${sign(m.groupValues[1]) ?: m.groupValues[1]}\"" }
                else -> sign(line.trim()) ?: line
            }
        }
    }

    /** 이 영상이 가진 목록뿐 — 원본 단 · 호환 단은 기록된 키 그대로, 사다리는 준비됐을 때 그 빌드 폴더의 `.m3u8` */
    private fun isOwnPlaylist(video: Video, name: String): Boolean {
        val key = keys.folder(video) + name
        if (key == video.origKey || (key == video.compatKey && video.compatStatus == TrackStatus.READY)) return true
        val build = video.ladderBuild?.takeIf { video.ladderStatus == TrackStatus.READY } ?: return false
        return name.endsWith(".m3u8") && VideoResultVerifier.allowedName(VideoLane.LADDER, build, name)
    }

    /** 조각 폴더 안에서 일꾼이 올릴 수 있는 이름과 같은 모양인가 */
    private fun allowed(video: Video, candidate: String): Boolean =
        VideoResultVerifier.allowedName(VideoLane.REMUX, null, candidate) || VideoResultVerifier.allowedName(VideoLane.COMPAT, null, candidate) ||
            VideoResultVerifier.allowedName(VideoLane.LADDER, video.ladderBuild, candidate)

    private companion object {
        const val MAX_PLAYLIST_BYTES = 1024 * 1024
        val MIN_SIGN: Duration = Duration.ofSeconds(1)

        /** 한 겹 파일 이름 — 슬래시 · 콜론 · 질의 · 역슬래시가 든 줄은 서명하지 않는다 */
        val PLAIN_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        val URI_ATTRIBUTE = Regex("URI=\"([^\"]*)\"")
    }
}
