package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.crypto.OpaqueUrlTokenCodec
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.ObjectProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@AutoConfiguration
@EnableConfigurationProperties(S3StorageProperties::class)
@ConditionalOnProperty(
    prefix = "skeleton.storage-s3",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class S3StorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun s3Client(properties: S3StorageProperties): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider(properties))
            .serviceConfiguration(s3Configuration(properties))

        properties.endpointOverride?.let { builder.endpointOverride(it) }

        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun s3Presigner(properties: S3StorageProperties): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider(properties))
            .serviceConfiguration(s3Configuration(properties))

        // presign URL 은 클라이언트(브라우저)가 여는 주소이므로 서버→S3 주소와 다를 수 있다 (presign.endpoint-override 가 우선)
        (properties.presign.endpointOverride ?: properties.endpointOverride)?.let { builder.endpointOverride(it) }

        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun storagePublicUrlResolver(
        properties: S3StorageProperties,
        opaqueUrlTokenCodec: ObjectProvider<OpaqueUrlTokenCodec>,
    ): StoragePublicUrlResolver =
        when {
            properties.publicUrl.baseUrl.isBlank() -> StoragePublicUrlResolver.NONE
            properties.publicUrl.strategy == S3StorageProperties.PublicUrlStrategy.RAW ->
                BaseUrlStoragePublicUrlResolver(properties.publicUrl.baseUrl)
            properties.publicUrl.strategy == S3StorageProperties.PublicUrlStrategy.OPAQUE -> {
                val codec = opaqueUrlTokenCodec.getIfAvailable()
                    ?: throw IllegalStateException("Opaque storage public URLs require an OpaqueUrlTokenCodec bean.")
                OpaqueStoragePublicUrlResolver(
                    baseUrl = properties.publicUrl.baseUrl,
                    tokenPathPrefix = properties.publicUrl.tokenPathPrefix,
                    tokenPurpose = properties.publicUrl.tokenPurpose,
                    tokenCodec = codec,
                )
            }
            else -> error("Unsupported storage public URL strategy '${properties.publicUrl.strategy}'.")
        }

    @Bean
    @ConditionalOnMissingBean(PresignedStorage::class)
    @ConditionalOnProperty(prefix = "skeleton.storage-s3", name = ["bucket"])
    fun s3PresignedStorageService(
        properties: S3StorageProperties,
        s3Client: S3Client,
        s3Presigner: S3Presigner,
        storagePublicUrlResolver: StoragePublicUrlResolver,
    ): PresignedStorage =
        S3PresignedStorageService(
            bucket = properties.bucket,
            s3Client = s3Client,
            presigner = s3Presigner,
            uploadPresignDuration = properties.presign.upload,
            downloadPresignDuration = properties.presign.download,
            multipartPartPresignDuration = properties.presign.multipartPart,
            publicUrlResolver = storagePublicUrlResolver,
        )

    private fun credentialsProvider(properties: S3StorageProperties): AwsCredentialsProvider {
        val c = properties.credentials
        return when {
            c.accessKeyId.isNotBlank() && c.secretAccessKey.isNotBlank() ->
                StaticCredentialsProvider.create(AwsBasicCredentials.create(c.accessKeyId, c.secretAccessKey))
            c.accessKeyId.isNotBlank() || c.secretAccessKey.isNotBlank() ->
                throw IllegalStateException("skeleton.storage-s3.credentials: access-key-id and secret-access-key must be set together.")
            c.profile.isNotBlank() -> ProfileCredentialsProvider.create(c.profile)
            else -> DefaultCredentialsProvider.builder().build()
        }
    }

    private fun s3Configuration(properties: S3StorageProperties): S3Configuration =
        S3Configuration.builder()
            .pathStyleAccessEnabled(properties.pathStyleAccessEnabled)
            .build()
}
