package dev.sumin.skeleton.video

import dev.sumin.skeleton.storage.StoragePublicProperties
import dev.sumin.skeleton.storage.StorageWebProperties
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.util.unit.DataSize

/** 영상 키 구역은 업로드 구역 · 공개 구역과 겹치지 않는다 — 겹치면 기동 실패 (영상 폴더가 남의 업로드로 읽히거나 공개 영역 정리가 영상을 지운다) */
class VideoPropertiesTest {
    private val publicOn = StoragePublicProperties(enabled = true, secret = "s".repeat(40), prefix = "public", baseUrl = "https://cdn.example.com")

    @Test
    fun `a video prefix that equals or contains the upload key prefix stops the start`() {
        val e = assertFailsWith<IllegalArgumentException> { VideoProperties(prefix = "uploads").requireDisjointFrom(StorageWebProperties()) }
        assertTrue("key-prefix" in e.message!!, e.message)
        assertFailsWith<IllegalArgumentException> { VideoProperties(prefix = "uploads/v").requireDisjointFrom(StorageWebProperties()) }
        assertFailsWith<IllegalArgumentException> { VideoProperties(prefix = "up").requireDisjointFrom(StorageWebProperties(keyPrefix = "up/files")) }
    }

    @Test
    fun `a video prefix that overlaps the public prefix stops the start only when the public area is on`() {
        val web = StorageWebProperties(`public` = publicOn)
        val e = assertFailsWith<IllegalArgumentException> { VideoProperties(prefix = "public/videos").requireDisjointFrom(web) }
        assertTrue("public.prefix" in e.message!!, e.message)
        VideoProperties(prefix = "public/videos").requireDisjointFrom(StorageWebProperties())
        VideoProperties().requireDisjointFrom(web)
    }

    @Test
    fun `limits that cannot work are refused when the properties are built`() {
        assertFailsWith<IllegalArgumentException> { VideoProperties(partSize = DataSize.ofMegabytes(4)) }   // S3 규약: 마지막 조각 말고는 5MB 이상
        assertFailsWith<IllegalArgumentException> { VideoProperties(maxBytes = DataSize.ofGigabytes(200)) }   // 16MB 조각이 10,000 개를 넘는다
        assertFailsWith<IllegalArgumentException> { VideoProperties(prefix = "  / ") }
        assertFailsWith<IllegalArgumentException> { VideoProperties(maxInFlight = 0) }
    }
}
