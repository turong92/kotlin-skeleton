package dev.sumin.skeleton.review.jdbc

import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import dev.sumin.skeleton.review.REVIEW_UPLOAD_KEY_MODULE
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 리뷰 사진으로 붙어 있는 저장 키 - 회원 업로드 정리가 「쓰이는 파일은 지우지 않는다」를 지키려고 묻는다 */
class JdbcReviewUploadedKeyUsage(private val jdbc: NamedParameterJdbcTemplate) : UploadedKeyUsage {
    override val answers: Set<String> = setOf(REVIEW_UPLOAD_KEY_MODULE)

    override fun inUse(keys: Collection<String>): Set<String> {
        if (keys.isEmpty()) return emptySet()
        return jdbc.queryForList("select storage_key from review_photos where storage_key in (:keys)", MapSqlParameterSource("keys", keys.toList()), String::class.java).filterNotNull().toSet()
    }
}
