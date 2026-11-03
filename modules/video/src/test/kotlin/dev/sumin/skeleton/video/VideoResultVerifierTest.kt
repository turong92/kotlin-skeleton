package dev.sumin.skeleton.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 일꾼이 올릴 수 있는 이름 · 형식 (영상 설계 4.2 — 허용 경로 패턴으로만 presign) */
class VideoResultVerifierTest {
    private val build = "0123456789abcdef0123456789abcdef"
    private val hash = "0123456789abcdef"

    @Test
    fun `remux 차선은 원본 단 조각 폴더 o-해시 와 포스터 p- 만 올린다`() {
        listOf("index.m3u8", "init.mp4", "s00000.m4s", "s00123.m4s").forEach { assertTrue(VideoResultVerifier.allowedName(VideoLane.REMUX, null, "o-$hash/$it"), it) }
        assertTrue(VideoResultVerifier.allowedName(VideoLane.REMUX, null, "o-$hash/index.m3u8"))
        assertTrue(VideoResultVerifier.allowedName(VideoLane.REMUX, null, "p-$hash.jpg"))
        assertFalse(VideoResultVerifier.allowedName(VideoLane.REMUX, null, "c-$hash/index.m3u8"), "호환판은 호환판 차선 몫")
        assertFalse(VideoResultVerifier.allowedName(VideoLane.REMUX, build, "h-$build/master.m3u8"))
    }

    @Test
    fun `호환판 차선은 c- 만`() {
        assertTrue(VideoResultVerifier.allowedName(VideoLane.COMPAT, null, "c-$hash/index.m3u8"))
        assertFalse(VideoResultVerifier.allowedName(VideoLane.COMPAT, null, "o-$hash/index.m3u8"), "원본판을 덮어쓰지 못한다")
        assertFalse(VideoResultVerifier.allowedName(VideoLane.COMPAT, null, "p-$hash.jpg"))
    }

    @Test
    fun `사다리 차선은 자기 빌드 폴더 한 겹 아래만`() {
        assertTrue(VideoResultVerifier.allowedName(VideoLane.LADDER, build, "h-$build/master.m3u8"))
        assertTrue(VideoResultVerifier.allowedName(VideoLane.LADDER, build, "h-$build/v720_000.m4s"))
        assertFalse(VideoResultVerifier.allowedName(VideoLane.LADDER, build, "h-${"f".repeat(32)}/master.m3u8"), "다른 빌드 폴더")
        assertFalse(VideoResultVerifier.allowedName(VideoLane.LADDER, build, "h-$build/a/b.m4s"), "더 깊은 경로")
        assertFalse(VideoResultVerifier.allowedName(VideoLane.LADDER, build, "h-$build/.."), "상위 폴더")
        assertFalse(VideoResultVerifier.allowedName(VideoLane.LADDER, build, "h-$build/.hidden.m4s"))
        assertFalse(VideoResultVerifier.allowedName(VideoLane.LADDER, build, "h-$build/x.exe"), "허용 확장자만")
        assertFalse(VideoResultVerifier.allowedName(VideoLane.LADDER, null, "h-$build/master.m3u8"), "빌드가 없으면 거절")
    }

    @Test
    fun `모양이 틀린 이름은 어느 차선에서도 거절`() {
        listOf(
            "o-${hash.uppercase()}/index.m3u8", "o-${hash.drop(1)}/index.m3u8", "o-$hash.mov", "o-$hash/index.m3u8/../x", "../o-$hash/index.m3u8",
            "/o-$hash/index.m3u8", "pages/x/o-$hash/index.m3u8", "o-$hash/index.m3u8\u0000", "", "o-$hash/index.m3u8 ",
            // 공개 한 덩어리 영상 파일은 어느 차선도 못 올린다 — 조각 폴더의 init.mp4 만 예외
            "o-$hash.mp4", "c-$hash.mp4", "o-$hash/video.mp4", "o-$hash/a/init.mp4", "o-$hash/s0.m4s",
        ).forEach { name ->
            VideoLane.entries.forEach { lane ->
                assertFalse(VideoResultVerifier.allowedName(lane, build, name), "$lane 이 받으면 안 되는 이름: '$name'")
            }
        }
    }

    @Test
    fun `내용 형식은 이름 끝으로 정한다`() {
        assertEquals("application/vnd.apple.mpegurl", VideoResultVerifier.contentTypeOf("o-$hash/index.m3u8"))
        assertEquals("video/mp4", VideoResultVerifier.contentTypeOf("o-$hash/init.mp4"))
        assertEquals("video/mp4", VideoResultVerifier.contentTypeOf("o-$hash/s00001.m4s"))
        assertEquals("image/jpeg", VideoResultVerifier.contentTypeOf("p-$hash.jpg"))
        assertEquals("application/vnd.apple.mpegurl", VideoResultVerifier.contentTypeOf("h-$build/master.m3u8"))
        assertEquals("video/mp4", VideoResultVerifier.contentTypeOf("h-$build/seg_001.m4s"))
        assertNull(VideoResultVerifier.contentTypeOf("h-$build/x.exe"))
    }

    @Test
    fun `원본판 이름의 해시는 결과 SHA-256 앞 16자와 같아야 한다`() {
        val sha = "0123456789abcdef" + "f".repeat(48)
        assertTrue(VideoResultVerifier.hashMatches("o-$hash/index.m3u8", sha))
        assertFalse(VideoResultVerifier.hashMatches("o-${"a".repeat(16)}/index.m3u8", sha))
        assertFalse(VideoResultVerifier.hashMatches("o-$hash/index.m3u8", "zz"))
    }

    @Test
    fun `움직이는 이미지 차선은 display 점 mp4 와 webp 두 이름만 - 다른 차선은 이 이름을 못 올리고 이 차선은 다른 차선의 이름을 못 올린다`() {
        assertTrue(VideoResultVerifier.allowedName(VideoLane.ANIMATED, null, "display.mp4"))
        assertTrue(VideoResultVerifier.allowedName(VideoLane.ANIMATED, null, "display.webp"))
        listOf("display.gif", "display.jpg", "display.mp4/x", "../display.mp4", "x/display.mp4", "Display.mp4", "display.mp4 ", "thumb.jpg", "display.m3u8", "").forEach {
            assertFalse(VideoResultVerifier.allowedName(VideoLane.ANIMATED, null, it), "ANIMATED 가 받으면 안 되는 이름: '$it'")
        }
        listOf("o-$hash/index.m3u8", "o-$hash/init.mp4", "p-$hash.jpg", "c-$hash/index.m3u8").forEach {
            assertFalse(VideoResultVerifier.allowedName(VideoLane.ANIMATED, build, it), "ANIMATED 가 받으면 안 되는 이름: '$it'")
        }
        listOf(VideoLane.REMUX, VideoLane.COMPAT, VideoLane.LADDER).forEach { lane ->
            listOf("display.mp4", "display.webp").forEach { assertFalse(VideoResultVerifier.allowedName(lane, build, it), "$lane 이 받으면 안 되는 이름: $it") }
        }
        assertEquals("image/webp", VideoResultVerifier.contentTypeOf("display.webp"))
        assertEquals("video/mp4", VideoResultVerifier.contentTypeOf("display.mp4"))
    }
}
