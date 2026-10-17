package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploads

/** 서명 열람이 가리키는 첨부 목록 - 공개된 항목의 현재 첨부, 또는 대기 중인 고친 판의 첨부(순번이 따로다) */
enum class AttachmentSource { CURRENT, EDIT }

/** 비회원이 서명 열람에서 어느 비밀로 증명하는가 - 글 주인(글의 비밀) 또는 댓글 작성자(댓글의 비밀). 하나로 정해 남의 행에 틀린 시도를 쌓지 않는다 */
enum class AttachmentRole { OWNER, AUTHOR }

/** 짧게 읽는 서명 주소 - 승인 전 · 판 사진을 관리하는 사람이 볼 때 */
data class SignedAttachment(val url: String, val expiresInSeconds: Long)

/**
 * 업로드 모듈(storage)과 이어지는 자리 - 둘 다 없으면 board 는 지금처럼 키만 낸다.
 * [publicUrls] 는 공개 조건(글 · 댓글이 PUBLISHED)에 드는 항목에만 부른다 - 주소는 키에서 계산되므로 읽기만으로 쓸 수 있다.
 */
class BoardUploads(private val publicUploads: () -> PublicUploads? = { null }, private val access: () -> PrivateUploadAccess? = { null }) {
    fun publicUrls(keys: List<String>): Map<String, String> =
        if (keys.isEmpty()) emptyMap() else publicUploads()?.let { uploads -> keys.associateWith { uploads.urlOf(it) } } ?: emptyMap()

    fun signed(key: String): SignedAttachment? =
        access()?.let { reader -> SignedAttachment(reader.downloadUrl(key, reader.defaultTtl), reader.defaultTtl.seconds) }

    companion object {
        val NONE = BoardUploads()
    }
}
