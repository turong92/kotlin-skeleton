package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StoragePublicProperties
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 비공개 재생 목록이 열리는 경로의 앞부분 — 출입증이 이 뒤에 온다 */
internal const val PLAY_PATH = "/api/v1/videos/play/"

/**
 * 재생 허가 — 화면 부품(`@skeleton/video`)이 읽는 모양. [src] 는 원본 단 목록, [compat] 은 호환 단 목록(준비됐을 때만 — 원본이 이미 호환이면 없다),
 * [hls] 는 사다리 마스터 목록(준비됐을 때만), [big] 은 원본이 호환 조건 밖이라는 뜻이다. 공개 영상은 [expiresAt] 이 없다
 */
data class PlaybackGrant(
    val src: String,
    val compat: String?,
    val hls: String?,
    val poster: String?,
    val codec: String?,
    val w: Int?,
    val h: Int?,
    val durationMs: Long?,
    val big: Boolean,
    val expiresAt: Instant?,
)

/**
 * 재생 허가를 만드는 자리 — 기본은 [StorageVideoPlayback](공개 영상은 공개 주소, 비공개는 출입증 경로). 벤더 스트리밍은 올리기 · 변환 · 재생을 다 대신하므로
 * 이 빈 하나와 `VideoEvents` 로 끼운다. 호출자는 이미 [VideoAccessPolicy.canPlay] 와 `PLAYABLE` 을 확인했다
 */
fun interface VideoPlayback {
    fun grant(video: Video, viewer: String?): PlaybackGrant
}

class IssuedToken(val value: String, val expiresAt: Instant)

/** [remaining] 은 확인한 순간의 남은 수명 — 조각 주소를 이 수명으로 서명한다 */
class VerifiedToken(val videoId: String, val expiresAt: Instant, val remaining: Duration)

/**
 * 비공개 재생 출입증 `v1.<영상 id>.<만료 초>.<mac>` — mac 은 앞 세 칸의 HMAC-SHA256(base64url). 서버에 저장하지 않고 경로에 실린다(사파리 기본 재생은 헤더를 못 붙인다).
 * 위조 · 만료 · 모양이 틀린 것은 모두 `null` 이라 호출한 쪽이 같은 404 로 답한다(어느 쪽이 틀렸는지 알려주지 않는다)
 */
class VideoPlaybackTokens(secret: String, private val time: TimeProvider) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC)

    init {
        require(secret.length >= MIN_SECRET_LENGTH) { "skeleton.video.playback.secret must be at least $MIN_SECRET_LENGTH characters" }
    }

    fun issue(videoId: String, ttl: Duration): IssuedToken {
        require(ID.matches(videoId)) { "a video id is a plain identifier" }
        require(!ttl.isZero && !ttl.isNegative) { "a token lifetime must be positive" }
        val expiresAt = time.now().plus(ttl).truncatedTo(ChronoUnit.SECONDS)
        val body = "v1.$videoId.${expiresAt.epochSecond}"
        return IssuedToken("$body.${mac(body)}", expiresAt)
    }

    fun verify(token: String): VerifiedToken? {
        val parts = token.split('.')
        if (parts.size != 4 || parts[0] != "v1" || !ID.matches(parts[1]) || !EXPIRY.matches(parts[2])) return null
        if (!MessageDigest.isEqual(mac("v1.${parts[1]}.${parts[2]}").toByteArray(Charsets.UTF_8), parts[3].toByteArray(Charsets.UTF_8))) return null
        val expiresAt = Instant.ofEpochSecond(parts[2].toLong())
        val now = time.now()
        if (!now.isBefore(expiresAt)) return null
        return VerifiedToken(parts[1], expiresAt, Duration.between(now, expiresAt))
    }

    private fun mac(body: String): String {
        val hmac = Mac.getInstance(HMAC).apply { init(key) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(body.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val MIN_SECRET_LENGTH = 32
        private const val HMAC = "HmacSHA256"
        private val ID = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val EXPIRY = Regex("^[0-9]{1,12}$")
    }
}

/** 영상 폴더 안의 상대 이름 — 키가 폴더 밖이면 만들 수 없다 */
private fun relative(keys: VideoKeys, video: Video, key: String): String {
    val folder = keys.folder(video)
    check(key.startsWith(folder)) { "video ${video.id} has a key outside its folder" }
    return key.removePrefix(folder)
}

private fun publicArea(props: StoragePublicProperties): StoragePublicProperties =
    props.also { check(it.enabled) { "a public video needs skeleton.storage.web.public.enabled=true to be played" } }

/**
 * 기본 재생 허가. 공개 영상 = storage 공개 영역의 주소(CDN 이 캐시 — 주소를 알면 누구나), 비공개 영상 = 출입증이 **경로**에 든 서버 주소
 * (`/api/v1/videos/play/<토큰>/o-<해시>/index.m3u8`, 열 때 조각 줄을 출입증 남은 수명의 서명 주소로 바꿔 쓴다 — [VideoPlaylists]). 포스터는 비공개면 같은 수명으로 서명한다
 */
class StorageVideoPlayback(
    private val props: VideoProperties,
    private val keys: VideoKeys,
    private val storage: PresignedStorage,
    private val publicProps: () -> StoragePublicProperties,
    private val tokens: VideoPlaybackTokens,
) : VideoPlayback {
    override fun grant(video: Video, viewer: String?): PlaybackGrant {
        val orig = checkNotNull(video.origKey) { "playable video without an original tier" }
        val compat = video.compatKey.takeIf { video.compatStatus == TrackStatus.READY }
        val hls = video.ladderBuild?.takeIf { video.ladderStatus == TrackStatus.READY }?.let { "${keys.folder(video)}h-$it/master.m3u8" }
        val big = video.compatStatus != TrackStatus.SKIPPED
        return if (video.visibility == VideoVisibility.PUBLIC) {
            val pub = publicArea(publicProps())
            PlaybackGrant(pub.absoluteUrl(orig), compat?.let(pub::absoluteUrl), hls?.let(pub::absoluteUrl), video.posterKey?.let(pub::absoluteUrl), video.srcCodecString, video.srcWidth, video.srcHeight, video.srcDurationMs, big, null)
        } else {
            val token = tokens.issue(video.id, props.playback.ttl)
            fun url(key: String) = "$PLAY_PATH${token.value}/${relative(keys, video, key)}"
            val poster = video.posterKey?.let { storage.presignDownload(PresignedDownloadRequest(ObjectKey(it), expiresIn = props.playback.ttl)).url.toString() }
            PlaybackGrant(url(orig), compat?.let(::url), hls?.let(::url), poster, video.srcCodecString, video.srcWidth, video.srcHeight, video.srcDurationMs, big, token.expiresAt)
        }
    }
}

/** 기본 [VideoWorkerInputs] — 공개 영상은 공개 주소, 비공개 영상은 [ttl] 수명의 출입증이 든 서버 경로 */
class StorageVideoWorkerInputs(
    private val keys: VideoKeys,
    private val tokens: VideoPlaybackTokens,
    private val publicProps: () -> StoragePublicProperties,
) : VideoWorkerInputs {
    override fun originalPlaylistUrl(video: Video, ttl: Duration): String {
        val orig = checkNotNull(video.origKey) { "video without an original tier" }
        if (video.visibility == VideoVisibility.PUBLIC) return publicArea(publicProps()).absoluteUrl(orig)
        return "$PLAY_PATH${tokens.issue(video.id, ttl).value}/${relative(keys, video, orig)}"
    }
}
