# Crypto

`modules:crypto` provides optional AES-GCM text encryption for values that must
be stored encrypted and later restored by the application.

This is different from redaction:

- redaction hides values in logs, alerts, and debug views.
- encryption protects persisted or transported values while keeping them
  recoverable by the application.

The old `be-api` `EncryptUtils` pattern used XOR + Base64 mostly to hide raw S3
keys in public URLs. The skeleton does not copy that algorithm. Use AES-GCM for
secrets and use storage URL adapters for public asset URL generation.

## Configuration

Configure a primary key id and one or more base64-encoded AES keys. Keys must
decode to 16, 24, or 32 bytes.

```yaml
skeleton:
  crypto:
    primary-key-id: local
    keys:
      local: ${SKELETON_CRYPTO_KEY_LOCAL}
      previous: ${SKELETON_CRYPTO_KEY_PREVIOUS:}
```

New encryption uses `primary-key-id`. Decryption reads the key id from the
ciphertext envelope, so old keys can remain configured during rotation.
If no keys are configured, the module stays passive and does not create a
`TextEncryptor`. If keys are configured with an invalid size or an unknown
primary key id, startup fails fast.

Ciphertext format:

```text
aes-gcm:v1:<keyId>:<base64url(iv + ciphertext + tag)>
```

## Opaque URL Tokens

`OpaqueUrlTokenCodec` creates URL-safe path tokens for cases where public routes
should not expose raw IDs or object keys. The token payload contains:

- `value`: route target, object key, or other app-owned reference.
- `purpose`: a required purpose such as `frontend-route` or
  `storage-public-url`.
- `expiresAt`: optional expiry checked during decoding.
- `metadata`: optional string metadata for routing hints.

Example:

```kotlin
val token = codec.encode(
    OpaqueUrlTokenPayload(
        value = "contents/video-1/master.m3u8",
        purpose = "storage-public-url",
        expiresAt = Instant.now().plusSeconds(600),
    ),
)

val payload = codec.decode(token, expectedPurpose = "storage-public-url")
```

Opaque tokens hide URL shape. They do not replace authorization, CDN signed
URLs, signed cookies, or backend permission checks.

## Service Usage

```kotlin
class SecretService(
    private val textEncryptor: TextEncryptor,
) {
    fun saveToken(token: String): String =
        textEncryptor.encrypt(token)

    fun readToken(cipherText: String): String =
        textEncryptor.decrypt(cipherText)
}
```

## JPA

Use the base converter only for fields that intentionally need encryption.

```kotlin
class CustomerSecretConverter(
    textEncryptor: TextEncryptor,
) : EncryptedStringJpaAttributeConverter(textEncryptor)

@Convert(converter = CustomerSecretConverter::class)
@Column(name = "provider_token")
var providerToken: String? = null
```

Do not auto-apply encryption to every `String`; it will break ordinary queries,
indexes, sorting, and debugging.

## Spring Data JDBC

Use `EncryptedString` when a value should be encrypted by JDBC converters:

```kotlin
data class ProviderCredential(
    val id: String,
    val accessToken: EncryptedString,
)
```

When a `TextEncryptor` exists, `EncryptedStringWritingConverter` and
`EncryptedStringReadingConverter` are registered as beans. Add them to the
application's JDBC custom conversions if the app owns its own conversion
configuration.

## Operational Notes

- Store keys in environment variables, AWS SSM, Vault, or another secret
  provider. Do not commit them.
- Encryption is not a substitute for redaction. Decrypted values can still leak
  through logs unless log/alert boundaries redact them.
- Opaque URL tokens are not a substitute for access control. Use them with
  private origins, permission checks, and expiring CDN/backend access where the
  content is not public.
- Use hashing, not encryption, when the application never needs to recover the
  original value.
