package dev.sumin.skeleton.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonDocumentTest {
    private val codec = JacksonJsonCodec()

    @Test
    fun `json document keeps raw json queryable and canonical`() {
        val document = codec.parse(
            """
            {
              "z": 2,
              "a": {
                "name": "sumin",
                "roles": ["USER", "ADMIN"]
              }
            }
            """.trimIndent(),
        )

        assertEquals("sumin", document.textAt("/a/name"))
        assertEquals("ADMIN", document.textAt("/a/roles/1"))
        assertTrue(document.has("/z"))
        assertFalse(document.has("/missing"))
        assertEquals("""{"a":{"name":"sumin","roles":["USER","ADMIN"]},"z":2}""", codec.canonicalString(document))
    }

    @Test
    fun `json codec converts between dto and json document without exposing map object`() {
        val dto = SamplePayload(name = "sample", count = 3)
        val document = codec.toDocument(dto)

        assertEquals("sample", document.textAt("/name"))
        assertEquals(3, document.requiredAt("/count").intValue())
        assertEquals(dto, codec.fromDocument(document, SamplePayload::class))
    }

    data class SamplePayload(
        val name: String,
        val count: Int,
    )
}
