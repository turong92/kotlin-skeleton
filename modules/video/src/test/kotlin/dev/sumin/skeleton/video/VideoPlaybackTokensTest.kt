package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.video.PlaybackFixtures.SECRET
import dev.sumin.skeleton.video.PlaybackFixtures.t0
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 비공개 재생 출입증 `v1.<id>.<만료>.<mac>` — 위조 · 만료 · 다른 영상 id 는 풀리지 않는다(풀리지 않으면 아무것도 서명되지 않는다) */
class VideoPlaybackTokensTest {
    private var now: Instant = t0
    private val tokens = VideoPlaybackTokens(SECRET, TimeProvider { now })

    @Test
    fun `a token names its video and expires after the given lifetime`() {
        val issued = tokens.issue("vid_A-1", Duration.ofHours(4))

        assertEquals(t0.plus(Duration.ofHours(4)), issued.expiresAt)
        assertEquals("v1.vid_A-1.${issued.expiresAt.epochSecond}", issued.value.substringBeforeLast('.'))
        val verified = assertNotNull(tokens.verify(issued.value))
        assertEquals("vid_A-1", verified.videoId)
        assertEquals(issued.expiresAt, verified.expiresAt)
    }

    @Test
    fun `a forged token does not verify - changed mac, another video id, a longer expiry, another secret`() {
        val issued = tokens.issue("vid1", Duration.ofHours(1))
        val (version, id, exp, mac) = issued.value.split('.')

        assertNull(tokens.verify("$version.$id.$exp.${mac.dropLast(1)}${if (mac.last() == 'A') 'B' else 'A'}"))
        assertNull(tokens.verify("$version.vid2.$exp.$mac"))
        assertNull(tokens.verify("$version.$id.${exp.toLong() + 3600}.$mac"))
        assertNull(VideoPlaybackTokens("z".repeat(40), TimeProvider { now }).verify(issued.value))
    }

    @Test
    fun `an expired token does not verify, at the expiry instant either`() {
        val issued = tokens.issue("vid1", Duration.ofHours(1))

        now = t0.plus(Duration.ofMinutes(59))
        assertNotNull(tokens.verify(issued.value))
        now = issued.expiresAt
        assertNull(tokens.verify(issued.value))
        now = issued.expiresAt.plusSeconds(1)
        assertNull(tokens.verify(issued.value))
    }

    @Test
    fun `garbage is not a token`() {
        listOf("", "v1", "v1.a.b", "v2.vid1.1.mac", "v1.vid1.notanumber.mac", "v1.vid1.99999999999.", "v1..99999999999.mac", "v1.vid1.99999999999.mac.extra", "v1.vi d.99999999999.mac")
            .forEach { assertNull(tokens.verify(it), it) }
    }

    @Test
    fun `a secret shorter than 32 characters is refused`() {
        assertFailsWith<IllegalArgumentException> { VideoPlaybackTokens("short", TimeProvider { now }) }
    }
}
