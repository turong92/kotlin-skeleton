package dev.sumin.skeleton.json

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonPersistenceConvertersTest {
    private val codec = JacksonJsonCodec()

    @Test
    fun `jpa json document converter stores canonical json strings`() {
        val converter = JsonDocumentJpaAttributeConverter(codec)
        val document = codec.parse("""{"b":2,"a":1}""")

        val stored = converter.convertToDatabaseColumn(document)
        val restored = converter.convertToEntityAttribute(stored)

        assertEquals("""{"a":1,"b":2}""", stored)
        assertEquals(1, restored?.requiredAt("/a")?.intValue())
    }

    @Test
    fun `jpa versioned json document converter stores envelope`() {
        val converter = VersionedJsonDocumentJpaAttributeConverter(codec)
        val document = VersionedJsonDocument(
            type = "sample.payload",
            version = 3,
            payload = codec.parse("""{"name":"sample"}"""),
            metadata = mapOf("source" to "test"),
        )

        val restored = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(document))

        assertEquals("sample.payload", restored?.type)
        assertEquals(3, restored?.version)
        assertEquals("sample", restored?.payload?.textAt("/name"))
        assertEquals("test", restored?.metadata?.get("source"))
    }

    @Test
    fun `jdbc converters use same json representation`() {
        val writer = JsonDocumentWritingConverter(codec)
        val reader = JsonDocumentReadingConverter(codec)

        val stored = writer.convert(codec.parse("""{"z":2,"a":1}"""))
        val restored = reader.convert(stored)

        assertEquals("""{"a":1,"z":2}""", stored)
        assertEquals(2, restored.requiredAt("/z").intValue())
    }
}
