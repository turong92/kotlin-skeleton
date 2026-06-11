package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
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

        properties.endpointOverride?.let { builder.endpointOverride(it) }

        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun storagePublicUrlResolver(properties: S3StorageProperties): StoragePublicUrlResolver =
        if (properties.publicUrl.baseUrl.isBlank()) {
            StoragePublicUrlResolver.NONE
        } else {
            BaseUrlStoragePublicUrlResolver(properties.publicUrl.baseUrl)
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

    private fun credentialsProvider(properties: S3StorageProperties): AwsCredentialsProvider =
        if (properties.credentials.profile.isBlank()) {
            DefaultCredentialsProvider.builder().build()
        } else {
            ProfileCredentialsProvider.create(properties.credentials.profile)
        }

    private fun s3Configuration(properties: S3StorageProperties): S3Configuration =
        S3Configuration.builder()
            .pathStyleAccessEnabled(properties.pathStyleAccessEnabled)
            .build()
}
