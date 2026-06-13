package dev.sumin.skeleton.json

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.springframework.core.convert.converter.Converter as SpringConverter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter

@Converter(autoApply = false)
class JsonDocumentJpaAttributeConverter(
    private val codec: JsonCodec = JacksonJsonCodec(),
) : AttributeConverter<JsonDocument, String> {
    override fun convertToDatabaseColumn(attribute: JsonDocument?): String? =
        attribute?.let(codec::canonicalString)

    override fun convertToEntityAttribute(dbData: String?): JsonDocument? =
        dbData?.takeIf { it.isNotBlank() }?.let(codec::parse)
}

@Converter(autoApply = false)
class VersionedJsonDocumentJpaAttributeConverter(
    private val codec: JsonCodec = JacksonJsonCodec(),
) : AttributeConverter<VersionedJsonDocument, String> {
    override fun convertToDatabaseColumn(attribute: VersionedJsonDocument?): String? =
        attribute?.let { codec.canonicalString(codec.toDocument(it)) }

    override fun convertToEntityAttribute(dbData: String?): VersionedJsonDocument? =
        dbData?.takeIf { it.isNotBlank() }
            ?.let(codec::parse)
            ?.let { codec.fromDocument(it, VersionedJsonDocument::class) }
}

@WritingConverter
class JsonDocumentWritingConverter(
    private val codec: JsonCodec = JacksonJsonCodec(),
) : SpringConverter<JsonDocument, String> {
    override fun convert(source: JsonDocument): String =
        codec.canonicalString(source)
}

@ReadingConverter
class JsonDocumentReadingConverter(
    private val codec: JsonCodec = JacksonJsonCodec(),
) : SpringConverter<String, JsonDocument> {
    override fun convert(source: String): JsonDocument =
        codec.parse(source)
}

@WritingConverter
class VersionedJsonDocumentWritingConverter(
    private val codec: JsonCodec = JacksonJsonCodec(),
) : SpringConverter<VersionedJsonDocument, String> {
    override fun convert(source: VersionedJsonDocument): String =
        codec.canonicalString(codec.toDocument(source))
}

@ReadingConverter
class VersionedJsonDocumentReadingConverter(
    private val codec: JsonCodec = JacksonJsonCodec(),
) : SpringConverter<String, VersionedJsonDocument> {
    override fun convert(source: String): VersionedJsonDocument =
        codec.fromDocument(codec.parse(source), VersionedJsonDocument::class)
}
