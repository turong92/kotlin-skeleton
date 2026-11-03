package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

enum class VideoErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    INVALID("VIDEO.INVALID", HttpStatus.BAD_REQUEST, "요청이 올바르지 않다"),
    UNSUPPORTED("VIDEO.UNSUPPORTED", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MP4 · MOV 영상만 받는다"),
    TOO_LARGE("VIDEO.TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "영상 파일이 너무 크다"),
    FORBIDDEN("VIDEO.FORBIDDEN", HttpStatus.FORBIDDEN, "이 영상을 올릴 수 없다"),
    QUOTA("VIDEO.QUOTA", HttpStatus.PAYLOAD_TOO_LARGE, "남은 용량이 모자라다"),
    TOO_MANY_UPLOADS("VIDEO.TOO_MANY_UPLOADS", HttpStatus.TOO_MANY_REQUESTS, "동시에 올릴 수 있는 영상 수를 넘었다"),
    NOT_FOUND("VIDEO.NOT_FOUND", HttpStatus.NOT_FOUND, "그런 영상이 없다"),
    BAD_STATE("VIDEO.BAD_STATE", HttpStatus.CONFLICT, "지금 상태에서는 할 수 없다"),
    PART_RANGE("VIDEO.PART_RANGE", HttpStatus.BAD_REQUEST, "조각 번호가 범위 밖이다"),
    PARTS_INVALID("VIDEO.PARTS_INVALID", HttpStatus.BAD_REQUEST, "조각 목록이 올린 조각과 맞지 않다"),
    UPLOAD_INCOMPLETE("VIDEO.UPLOAD_INCOMPLETE", HttpStatus.BAD_REQUEST, "올리기를 마칠 수 없다 — 조각을 다시 확인한다"),
    SIZE_MISMATCH("VIDEO.SIZE_MISMATCH", HttpStatus.BAD_REQUEST, "올린 파일 크기가 처음 말한 크기와 다르다"),
}

class VideoInvalidException(why: String) : ApplicationException("video invalid: $why", VideoErrorCode.INVALID)

class VideoUnsupportedException : ApplicationException("video unsupported", VideoErrorCode.UNSUPPORTED)

class VideoTooLargeException(maxBytes: Long) :
    ApplicationException("video too large", VideoErrorCode.TOO_LARGE, data = mapOf("maxBytes" to maxBytes))

class VideoForbiddenException : ApplicationException("video upload not allowed", VideoErrorCode.FORBIDDEN)

class VideoQuotaException : ApplicationException("video quota", VideoErrorCode.QUOTA)

class VideoTooManyUploadsException(max: Int) :
    ApplicationException("video too many uploads", VideoErrorCode.TOO_MANY_UPLOADS, data = mapOf("max" to max))

/** 남의 · 지운 · 없는 영상을 하나로 답한다 */
class VideoNotFoundException : ApplicationException("video not found", VideoErrorCode.NOT_FOUND)

class VideoBadStateException(status: VideoStatus) :
    ApplicationException("video bad state: $status", VideoErrorCode.BAD_STATE, data = mapOf("status" to status.name))

class VideoPartRangeException(partCount: Int) :
    ApplicationException("video part range", VideoErrorCode.PART_RANGE, data = mapOf("partCount" to partCount))

class VideoPartsInvalidException(partCount: Int) :
    ApplicationException("video parts invalid", VideoErrorCode.PARTS_INVALID, data = mapOf("partCount" to partCount))

class VideoUploadIncompleteException(cause: Throwable?) :
    ApplicationException("video upload incomplete", VideoErrorCode.UPLOAD_INCOMPLETE, cause = cause)

class VideoSizeMismatchException : ApplicationException("video size mismatch", VideoErrorCode.SIZE_MISMATCH)
