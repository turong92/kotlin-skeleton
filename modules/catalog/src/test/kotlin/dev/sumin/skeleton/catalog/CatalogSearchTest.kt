package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.common.search.SearchIndexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 상품이 검색 색인에 들어가고 나오는 규칙: 만들기 · 고치기 · 판매 여부. 판매 중지된 상품은 색인에 없다(= 보여도 되는 문서만). */
class CatalogSearchTest {
    private class RecordingIndexer : SearchIndexer {
        val docs = linkedMapOf<Pair<String, String>, SearchDocument>()
        val deletes = mutableListOf<Pair<String, String>>()
        override fun upsert(document: SearchDocument) { docs[document.type to document.id] = document }
        override fun delete(type: String, id: String) { docs.remove(type to id); deletes += type to id }
    }

    private val store = FakeCatalogStore()
    private val time = MutableTime()
    private val indexer = RecordingIndexer()
    private val admin = CatalogAdmin(store, time, RecordingAudit(), searchIndexer = { indexer }, defaultLocale = "ko")

    @Test
    fun `a new product is pushed with the default-language name as title, every name and description as body, and its kind as scope`() {
        admin.createProduct("adm_1", ProductInput("basic", "SUBSCRIPTION", 1, texts = TEXTS))
        val doc = indexer.docs.getValue("product" to "basic")
        assertEquals("베이직", doc.title)
        assertTrue("Basic" in doc.body && "The basics" in doc.body && "베이직" in doc.body, doc.body)
        assertEquals("SUBSCRIPTION", doc.scope)
        assertNull(doc.ownerId, "a product has no owner - everyone who may search sees it")
    }

    @Test
    fun `the title falls back to the first language when the default language has no name`() {
        val en = CatalogAdmin(store, time, RecordingAudit(), searchIndexer = { indexer }, defaultLocale = "ja")
        en.createProduct("adm_1", ProductInput("basic", "ONE_TIME", null, texts = TEXTS))
        assertEquals("Basic", indexer.docs.getValue("product" to "basic").title)
    }

    @Test
    fun `editing the texts replaces the document and a later update time wins`() {
        admin.createProduct("adm_1", ProductInput("basic", "SUBSCRIPTION", 1, texts = TEXTS))
        time.current = time.current.plusSeconds(60)
        admin.updateProduct("adm_1", "basic", ProductPatch(texts = mapOf("ko" to ProductTextInput("새 베이직", "새 설명"))))
        val doc = indexer.docs.getValue("product" to "basic")
        assertEquals("새 베이직", doc.title)
        assertTrue("새 설명" in doc.body && "Basic" !in doc.body)
        assertEquals(time.current, doc.updatedAt)
    }

    @Test
    fun `stopping the sale deletes the document and selling again pushes it back`() {
        admin.createProduct("adm_1", ProductInput("basic", "SUBSCRIPTION", 1, texts = TEXTS))
        time.current = time.current.plusSeconds(60)
        admin.updateProduct("adm_1", "basic", ProductPatch(selling = false))
        assertTrue(indexer.docs.isEmpty())
        assertEquals(listOf("product" to "basic"), indexer.deletes)
        time.current = time.current.plusSeconds(60)
        admin.updateProduct("adm_1", "basic", ProductPatch(selling = true))
        assertEquals("베이직", indexer.docs.getValue("product" to "basic").title)
    }

    @Test
    fun `an app without search keeps working - no indexer, nothing happens`() {
        val plain = CatalogAdmin(store, time, RecordingAudit())
        plain.createProduct("adm_1", ProductInput("basic", "SUBSCRIPTION", 1, texts = TEXTS))
        plain.updateProduct("adm_1", "basic", ProductPatch(selling = false))
        assertTrue(indexer.docs.isEmpty())
    }

    @Test
    fun `the source pages selling products in code order and leaves out stopped ones`() {
        listOf("c", "a", "b", "d").forEachIndexed { i, code -> admin.createProduct("adm_1", ProductInput(code, "ONE_TIME", null, texts = TEXTS)) }
        admin.updateProduct("adm_1", "b", ProductPatch(selling = false))
        val source = CatalogSearchSource(store, "ko")
        assertEquals("product", source.type)
        val first = source.page(null, 2)
        assertEquals(listOf("a", "c"), first.documents.map { it.id })
        val second = source.page(first.next, 2)
        assertEquals(listOf("d"), second.documents.map { it.id })
        assertNull(second.next)
    }
}
