package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.upload.UploadImageStatus
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.web.StorageErrorCode
import dev.sumin.skeleton.storage.web.StorageValidationIssue

/** 완료 호출의 결과 - [contentType] 은 내용으로 판정한 형식(zip · OLE 계열과 판정 못 한 표 밖 형식은 선언 그대로), [image] 는 이미지 모듈이 알려 주는 상태(없으면 null) */
data class UploadCompletionResult(val key: String, val contentType: String?, val sizeBytes: Long, val image: UploadImageStatus?)

/**
 * 직접 올림이 끝났다는 호출을 받아 올라온 바이트를 검사한다: 있는지(HEAD) -> 크기 -> 앞 256KB(범위 GET) -> 검사기들 -> 통과하면 [UploadCompletionListener].
 * 소유 확인은 부르는 쪽(컨트롤러)이 이미 끝냈다 - 이 서비스는 받은 키 **하나만** 읽고 거절하면 그 하나만 지운다.
 * 이미지 상태 줄이 이미 있으면 다시 읽지 않고 그대로 답한다(멱등).
 */
class StorageUploadCompletion(
    private val storage: ObjectStorage,
    private val inspectors: () -> List<UploadInspector> = { emptyList() },
    private val listeners: () -> List<UploadCompletionListener> = { emptyList() },
    private val images: () -> UploadImages? = { null },
) {
    private val builtIn = MagicBytesInspector()

    /** [maxSizeBytes] 를 넘으면 거절 · 삭제한다. 없는 객체는 404 `STORAGE.OBJECT_NOT_FOUND`(지우지 않는다) */
    fun complete(key: ObjectKey, maxSizeBytes: Long): UploadCompletionResult {
        val meta = storage.metadata(key) ?: throw ApplicationException("Object not found: ${key.value}", StorageErrorCode.OBJECT_NOT_FOUND)
        val size = meta.sizeBytes ?: 0L
        images()?.status(key.value)?.let { return UploadCompletionResult(key.value, meta.contentType, size, it) }
        if (size > maxSizeBytes) {
            reject(key, StorageFileValidationErrorCode.SIZE_TOO_LARGE.name, "File size $size exceeds maximum $maxSizeBytes.")
        }
        val head = if (size == 0L) ByteArray(0) else storage.read(key, 0L until minOf(size, UploadMagicBytes.HEAD_BYTES.toLong())).use { it.readNBytes(UploadMagicBytes.HEAD_BYTES) }
        val kind = UploadMagicBytes.detect(head)
        val inspected = InspectedUpload(key, meta.contentType, kind?.mime, size, head) { storage.read(key) }
        for (inspector in listOf<UploadInspector>(builtIn) + inspectors()) {
            val verdict = inspector.inspect(inspected)
            if (verdict is InspectionVerdict.Reject) reject(key, verdict.code, verdict.message)
        }
        listeners().forEach { it.onAccepted(inspected) }
        val contentType = if (kind == null || kind == DetectedKind.ZIP || kind == DetectedKind.OLE) meta.contentType else kind.mime
        return UploadCompletionResult(key.value, contentType, size, images()?.status(key.value))
    }

    private fun reject(key: ObjectKey, code: String, message: String): Nothing {
        storage.delete(key)   // 방금 소유를 확인한 이 키 하나뿐이다
        throw ApplicationException(
            message = "File rejected: $code",
            errorCode = StorageErrorCode.FILE_REJECTED,
            data = mapOf("errors" to listOf(StorageValidationIssue(code, message))),
        )
    }
}
