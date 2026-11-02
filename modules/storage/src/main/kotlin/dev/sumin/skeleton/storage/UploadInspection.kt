package dev.sumin.skeleton.storage

import java.io.InputStream

/**
 * 완료 호출이 검사기에 넘기는 올라온 파일 - 키 · 선언한 형식(저장소에 적힌 Content-Type) · 판정한 형식(매직 바이트, 못 알아내면 null) · 크기 · 앞 [UploadMagicBytes.HEAD_BYTES] 바이트.
 * 전체를 읽어야 하는 검사기는 [open] 으로 스트림을 연다(호출한 쪽이 닫는다).
 */
class InspectedUpload(
    val key: ObjectKey,
    val declaredContentType: String?,
    val detectedContentType: String?,
    val sizeBytes: Long,
    val head: ByteArray,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}

sealed interface InspectionVerdict {
    data object Accept : InspectionVerdict

    /** [code] 는 사용자에게 보여도 되는 코드(`data.errors[].code`), [message] 는 개발자용 설명 */
    data class Reject(val code: String, val message: String) : InspectionVerdict
}

/**
 * 완료 호출에서 올라온 파일을 검사한다 - 하나라도 [InspectionVerdict.Reject] 면 **그 객체를 지우고** 400 으로 답한다.
 * 악성 파일 검사기(ClamAV 등) · 앱 규칙이 빈으로 끼운다. **부수 효과를 내지 않는다**(통과 뒤의 일은 [UploadCompletionListener]).
 * 내장 [MagicBytesInspector] 가 늘 먼저 돈다.
 */
fun interface UploadInspector {
    fun inspect(upload: InspectedUpload): InspectionVerdict
}

/** 모든 검사를 통과한 업로드를 받는다 - 상태 줄 · 후처리 작업을 여기서 시작한다. 같은 키로 여러 번 불릴 수 있다(완료 호출은 멱등) - 구현이 멱등이어야 한다 */
fun interface UploadCompletionListener {
    fun onAccepted(upload: InspectedUpload)
}

/** 선언한 형식과 내용의 서명이 같은 계열인가 - 알려진 형식 표([UploadMagicBytes]) 밖의 선언은 확인할 수 없어 통과시킨다 */
class MagicBytesInspector : UploadInspector {
    override fun inspect(upload: InspectedUpload): InspectionVerdict {
        val kind = UploadMagicBytes.detect(upload.head)
        val declared = UploadMagicBytes.declaredKinds(upload.declaredContentType)
        val shown = upload.declaredContentType?.substringBefore(';')?.trim().orEmpty()
        return when {
            (kind == DetectedKind.HTML || kind == DetectedKind.SVG) && (declared == null || kind !in declared) ->
                InspectionVerdict.Reject(StorageFileValidationErrorCode.CONTENT_MISMATCH.name, "Content looks like ${kind.mime} but was declared as '$shown'.")
            declared != null && kind == null ->
                InspectionVerdict.Reject(StorageFileValidationErrorCode.UNRECOGNIZED_CONTENT.name, "Content is not recognizable as '$shown'.")
            declared != null && kind !in declared ->
                InspectionVerdict.Reject(StorageFileValidationErrorCode.CONTENT_MISMATCH.name, "Content looks like ${kind?.mime} but was declared as '$shown'.")
            declared == null && UploadMagicBytes.declaredText(upload.declaredContentType) && kind != null ->
                InspectionVerdict.Reject(StorageFileValidationErrorCode.CONTENT_MISMATCH.name, "Content looks like ${kind.mime} but was declared as '$shown'.")
            declared == null && UploadMagicBytes.declaredText(upload.declaredContentType) && !UploadMagicBytes.looksLikeText(upload.head) ->
                InspectionVerdict.Reject(StorageFileValidationErrorCode.UNRECOGNIZED_CONTENT.name, "Content is not text although declared as '$shown'.")
            else -> InspectionVerdict.Accept
        }
    }
}
