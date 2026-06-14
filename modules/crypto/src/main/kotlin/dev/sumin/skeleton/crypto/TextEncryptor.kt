package dev.sumin.skeleton.crypto

interface TextEncryptor {
    fun encrypt(plainText: String): String

    fun decrypt(cipherText: String): String
}

class CryptoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
