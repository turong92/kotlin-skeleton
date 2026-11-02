package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.common.upload.UploadKeyHolder
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 이 앱이 자기 표(`notes.attachment_key`)에 업로드 키를 저장하므로 storage 의 버려진 회원 업로드 정리(`skeleton.storage.web.cleanup`)에 알린다:
 * 표지 하나(`notes`)와, 같은 이름에 대답하는 사용처. 둘 중 하나라도 빠지면 정리가 노트 첨부를 고아로 보고 지운다 - 표지가 있고 사용처가 없으면 정리를 켤 때 기동이 실패한다.
 * 내보내기 결과(`<key-prefix>/<계정 id>/exports/…`, [NoteExportJobHandler])는 어느 표에도 적히지 않지만 업로드 접두사 아래에 있으므로 「쓰이는 파일」로 대답한다 - 정리가 건드리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class NoteUploadKeys {
    @Bean
    fun noteUploadKeyHolder(): UploadKeyHolder = UploadKeyHolder(MODULE)

    @Bean
    fun noteUploadedKeyUsage(jdbc: NamedParameterJdbcTemplate): UploadedKeyUsage = object : UploadedKeyUsage {
        override val answers: Set<String> = setOf(MODULE)

        override fun inUse(keys: Collection<String>): Set<String> {
            if (keys.isEmpty()) return emptySet()
            val attached = jdbc.queryForList("select attachment_key from notes where attachment_key in (:keys)", MapSqlParameterSource("keys", keys.toList()), String::class.java).filterNotNull()
            return (attached + keys.filter { "/exports/" in it }).toSet()
        }
    }

    private companion object {
        const val MODULE = "notes"
    }
}
