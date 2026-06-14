package dev.sumin.skeleton.crypto

import jakarta.persistence.AttributeConverter
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter

open class EncryptedStringJpaAttributeConverter(
    private val encryptor: TextEncryptor,
) : AttributeConverter<String, String> {
    override fun convertToDatabaseColumn(attribute: String?): String? =
        attribute?.let(encryptor::encrypt)

    override fun convertToEntityAttribute(dbData: String?): String? =
        dbData?.let(encryptor::decrypt)
}

@JvmInline
value class EncryptedString(
    val value: String,
)

@WritingConverter
class EncryptedStringWritingConverter(
    private val encryptor: TextEncryptor,
) : Converter<EncryptedString, String> {
    override fun convert(source: EncryptedString): String =
        encryptor.encrypt(source.value)
}

@ReadingConverter
class EncryptedStringReadingConverter(
    private val encryptor: TextEncryptor,
) : Converter<String, EncryptedString> {
    override fun convert(source: String): EncryptedString =
        EncryptedString(encryptor.decrypt(source))
}
