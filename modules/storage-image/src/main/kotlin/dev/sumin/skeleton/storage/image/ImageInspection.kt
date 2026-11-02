package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.storage.InspectedUpload
import dev.sumin.skeleton.storage.InspectionVerdict
import dev.sumin.skeleton.storage.StorageFileValidationErrorCode
import dev.sumin.skeleton.storage.UploadCompletionListener
import dev.sumin.skeleton.storage.UploadInspector
import dev.sumin.skeleton.storage.UploadMagicBytes

/**
 * 완료 호출에서 이미지의 **크기를 머리만 보고** 확인한다 - 픽셀 폭탄(작은 파일, 머리에 3만 x 3만)과 바이트 상한을 디코드 없이 거절한다.
 * 읽을 수 없는 이미지는 여기서 거절하지 않는다(작업이 `REJECTED` 로 적는다) - 이 검사기는 상한만 본다.
 */
class ImageHeaderInspector(private val renderers: ImageRenderers, private val properties: ImageProperties) : UploadInspector {
    override fun inspect(upload: InspectedUpload): InspectionVerdict {
        // 이미지 종류에만 - PDF 같은 엔진 전용 종류의 상한(바이트 · 쪽 크기)은 그 엔진이 맡고, 썸네일을 못 만드는 것은 비동기 REJECTED 로 끝난다(원본 유지)
        val kind = UploadMagicBytes.detect(upload.head)?.takeIf { it in ImageRenderers.IMAGE_KINDS } ?: return InspectionVerdict.Accept
        val code = StorageFileValidationErrorCode.IMAGE_TOO_LARGE.name
        if (upload.sizeBytes > properties.maxSourceBytes) {
            return InspectionVerdict.Reject(code, "Image size ${upload.sizeBytes} exceeds maximum ${properties.maxSourceBytes}.")
        }
        val renderer = renderers.forKind(kind) ?: return InspectionVerdict.Accept
        val info = try {
            renderer.probe(upload.head, kind)
        } catch (e: ImageRejectedException) {
            return InspectionVerdict.Accept
        }
        if (info.width.toLong() * info.height > properties.maxPixels) {
            return InspectionVerdict.Reject(code, "Image is ${info.width}x${info.height} pixels, more than the ${properties.maxPixels} allowed.")
        }
        return InspectionVerdict.Accept
    }
}

/** 검사를 통과한 이미지에 상태 줄 + 작업을 시작한다. 같은 키로 여러 번 불려도 줄 하나 · 작업 하나다 */
class ImageCompletionListener(private val images: StorageImages, private val renderers: ImageRenderers) : UploadCompletionListener {
    override fun onAccepted(upload: InspectedUpload) {
        val kind = UploadMagicBytes.detect(upload.head) ?: return
        if (renderers.handles(kind)) images.register(upload.key.value)
    }
}
