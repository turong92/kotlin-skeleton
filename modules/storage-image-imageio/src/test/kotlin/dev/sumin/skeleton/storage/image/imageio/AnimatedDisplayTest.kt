package dev.sumin.skeleton.storage.image.imageio

import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.image.AnimatedDisplayConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 움직이는 표시본 변환 확장 지점 - 기본은 없음(사본 display 가 그대로 쓰인다). 변환은 사본이 이미 쓰이는 동안 뒤에서 하고,
 * 어떤 길로 실패해도 사용자의 이미지는 사본으로 남는다
 */
class AnimatedDisplayTest {
    private val gif = Photos.animatedGif(60, 40)

    @Test
    fun `converter is asked only after an animated image is READY with its copy display - still images never ask`() {
        val asked = mutableListOf<Pair<String, String>>()
        val statesWhenAsked = mutableListOf<UploadImageState>()
        lateinit var h: Harness
        h = Harness(converter = { hash, key -> asked += hash to key; statesWhenAsked += h.store.find(hash)!!.state })

        val row = h.run("uploads/7/g/x.gif", gif, "image/gif")
        h.run("uploads/7/j/x.jpg", Photos.jpeg(120, 80), "image/jpeg")

        assertEquals(listOf(row.keyHash to "uploads/7/g/x.gif"), asked)
        assertEquals(listOf(UploadImageState.READY), statesWhenAsked)
        assertEquals("gif", row.variants.getValue(UploadImages.DISPLAY).ext, "사본 display 가 먼저 쓰인다")
    }

    @Test
    fun `a converter that throws leaves the image READY with its copy display`() {
        val h = Harness(converter = { _, _ -> error("queue is down") })

        val row = h.run("uploads/7/g/x.gif", gif, "image/gif")

        assertEquals(UploadImageState.READY, row.state)
        assertEquals("image/gif", h.images.status("uploads/7/g/x.gif")!!.variants.getValue(UploadImages.DISPLAY).contentType)
    }

    @Test
    fun `using a converted display switches the variant to the new file and its content type - the status answer tells the format`() {
        val h = Harness()
        val row = h.run("uploads/7/g/x.gif", gif, "image/gif")

        assertTrue(h.displays.use(row.keyHash, "mp4"))

        val display = h.images.status("uploads/7/g/x.gif")!!.variants.getValue(UploadImages.DISPLAY)
        assertEquals("video/mp4", display.contentType)
        assertEquals("${h.displays.folder(row.keyHash)}display.mp4", display.key)
        assertEquals(60 to 40, display.width to display.height)
        assertTrue(h.images.status("uploads/7/g/x.gif")!!.variants.containsKey(UploadImages.THUMB), "썸네일은 그대로")
    }

    @Test
    fun `a converted display never takes the copy's own file name - and rows that are not animated READY are left alone`() {
        val h = Harness()
        val gifRow = h.run("uploads/7/g/x.gif", gif, "image/gif")
        val still = h.run("uploads/7/j/x.jpg", Photos.jpeg(120, 80), "image/jpeg")

        assertEquals("gif", h.displays.copyExtension(gifRow.keyHash))
        assertFalse(h.displays.use(gifRow.keyHash, "gif"), "사본과 같은 확장자면 사본을 덮어쓴다")
        assertEquals(null, h.displays.copyExtension(still.keyHash), "움직이는 이미지가 아니다")
        assertFalse(h.displays.use(still.keyHash, "mp4"))
        assertFalse(h.displays.use("0".repeat(64), "mp4"), "줄이 지워졌다(원본 회수)")
        assertEquals("gif", h.store.find(gifRow.keyHash)!!.variants.getValue(UploadImages.DISPLAY).ext)
    }
}
