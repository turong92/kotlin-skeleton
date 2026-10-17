package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploads

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
