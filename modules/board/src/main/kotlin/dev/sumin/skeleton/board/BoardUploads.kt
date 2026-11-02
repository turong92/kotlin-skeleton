package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploadImage
import dev.sumin.skeleton.common.upload.PublicUploads

/** 공개된 항목의 첨부 - [urls] 키 -> 공개 주소, [images] 변환이 끝난 이미지, [pending] 변환 중이라 아직 주소가 없는 키(순서는 첨부 순서) */
data class PublicAttachments(
    val urls: Map<String, String> = emptyMap(),
    val images: Map<String, PublicUploadImage> = emptyMap(),
    val pending: List<String> = emptyList(),
)

/** 서명 열람이 가리키는 첨부 목록 - 공개된 항목의 현재 첨부, 또는 대기 중인 고친 판의 첨부(순번이 따로다) */
enum class AttachmentSource { CURRENT, EDIT }

/** 비회원이 서명 열람에서 어느 비밀로 증명하는가 - 글 주인(글의 비밀) 또는 댓글 작성자(댓글의 비밀). 하나로 정해 남의 행에 틀린 시도를 쌓지 않는다 */
enum class AttachmentRole { OWNER, AUTHOR }

/** 짧게 읽는 서명 주소 - 승인 전 · 판 사진을 관리하는 사람이 볼 때 */
data class SignedAttachment(val url: String, val expiresInSeconds: Long)

/**
 * 업로드 모듈(storage)과 이어지는 자리 - 둘 다 없으면 board 는 지금처럼 키만 낸다.
 * [publicAttachments] 는 공개 조건(글 · 댓글이 PUBLISHED)에 드는 항목에만 부른다 - 주소는 키에서 계산되므로 읽기만으로 쓸 수 있다.
 */
class BoardUploads(private val publicUploads: () -> PublicUploads? = { null }, private val access: () -> PrivateUploadAccess? = { null }) {
    /**
     * 공개된 항목의 첨부를 남에게 보이는 모양으로 - 주소 · 변환이 끝난 이미지 · 변환 중인 키. 이미지 모듈이 있으면 **변환이 끝나지 않았거나 공개할 수 없는 이미지는 주소를 내지 않는다**
     * (그 주소는 아직 복사가 안 됐거나 영영 안 되는 404 이고, 원본을 대신 줄 수는 없다). 변환 중인 키는 [PublicAttachments.pending] 으로 알린다. 이미지 모듈이 없으면 키에서 계산한 주소 전부다.
     */
    fun publicAttachments(keys: List<String>): PublicAttachments {
        val uploads = publicUploads()?.takeIf { it.enabled() && keys.isNotEmpty() } ?: return PublicAttachments()
        val images = uploads.imagesOf(keys.filter { uploads.mayPublish(it) })
        val withheld = images.pending + images.refused
        val urls = keys.filter { uploads.mayPublish(it) && it !in withheld }.associateWith { uploads.urlOf(it) }
        return PublicAttachments(urls, images.ready, keys.filter { it in images.pending })
    }

    fun signed(key: String): SignedAttachment? =
        access()?.takeIf { it.enabled() }?.let { reader -> SignedAttachment(reader.downloadUrl(key, reader.defaultTtl), reader.defaultTtl.seconds) }

    companion object {
        val NONE = BoardUploads()
    }
}
