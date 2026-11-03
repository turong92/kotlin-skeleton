package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages

/**
 * 움직이는 이미지의 표시본(`display`)을 사본(메타데이터 제거 GIF · WebP) 대신 다른 형식 - 소리 없는 반복 MP4, 투명이면 움직이는 WebP - 으로 바꾸는 확장 지점.
 * 기본은 없음이라 사본이 그대로 쓰인다. 구현은 [ImageVariantProcessor] 가 사본을 올리고 줄을 `READY` 로 만든 **뒤에** 불린다 - 변환이 늦거나 실패해도 사본이 계속 쓰인다.
 * 던진 예외는 삼키고 로그만 남는다. 변환 결과를 쓰는 쪽은 [AnimatedDisplays.use]
 */
fun interface AnimatedDisplayConverter {
    fun requestConversion(keyHash: String, objectKey: String)

    companion object {
        val NONE = AnimatedDisplayConverter { _, _ -> }
    }
}

/** 변환 구현이 쓰는 면 - 변환본을 놓을 폴더와 표시본의 다른 형식 더하기. 상태 줄만 건드리고 저장소는 건드리지 않는다 */
class AnimatedDisplays(private val store: ImageStatusStore, private val properties: ImageProperties) {
    /** 변환본이 놓이는 저장소 폴더(슬래시로 끝남) - 변환본 키는 `<폴더>display.<확장자>` */
    fun folder(keyHash: String): String = variantFolder(properties.prefix, keyHash)

    /** 사본 display 의 확장자(= 원본 형식 `gif` · `webp`) - 움직이는 이미지로 `READY` 가 아니면 null(변환할 일이 없다). 변환본은 이 확장자를 쓸 수 없다: 사본을 덮어쓴다 */
    fun copyExtension(keyHash: String): String? = animatedRow(keyHash)?.format

    /**
     * `<폴더>display.<ext>` 를 표시본의 다른 형식(alt)으로 올린다 - 사본 display 는 그대로다(파일은 이미 올라가 있어야 한다). 사본과 같은 확장자는 거절하고, 움직이는 `READY` 줄이 아니면 false.
     * 이미 그 확장자로 바뀌어 있으면 true - 같은 호출이 또 와도 같은 결과다
     */
    fun use(keyHash: String, ext: String): Boolean {
        val row = animatedRow(keyHash) ?: return false
        if (ext.isEmpty() || ext.any { it !in 'a'..'z' && it !in '0'..'9' } || ext == row.format) return false
        val display = row.variants.getValue(UploadImages.DISPLAY)
        if (ext in display.alt) return true
        // 표시본은 이미지 사본 그대로 - 변환본은 같은 그림의 다른 형식(alt)으로 싣는다. `<img>` 로 그리는 소비자(board · review · 공개 영역)는 늘 사본을 받는다
        return store.replaceVariant(keyHash, UploadImages.DISPLAY, display.copy(alt = display.alt + ext))
    }

    private fun animatedRow(keyHash: String): ImageRow? = store.find(keyHash)?.takeIf { it.state == UploadImageState.READY && it.animated && UploadImages.DISPLAY in it.variants }
}
