package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.common.search.SearchSourcePage
import org.springframework.stereotype.Component

/** 노트 → 검색 문서. 주인 = 작성자라 남의 검색에는 나오지 않는다. 문서 id 는 노트 id */
fun Note.toSearchDocument() = SearchDocument(
    type = NoteSearchSource.TYPE,
    id = id.toString(),
    title = title,
    body = body,
    ownerId = ownerId,
    updatedAt = audit.updatedAt,
)

/** 재색인 · 검색을 나중에 붙인 앱의 채우기 - 노트 전부를 id 순으로 */
@Component
class NoteSearchSource(private val notes: NoteRepository) : SearchSource {
    override val type: String = TYPE

    override fun page(after: String?, limit: Int): SearchSourcePage {
        val rows = notes.pageAfter(after, limit + 1)
        val slice = rows.take(limit)
        return SearchSourcePage(slice.map { it.toSearchDocument() }, if (rows.size > limit) slice.last().id.toString() else null)
    }

    companion object {
        const val TYPE = "note"
    }
}
