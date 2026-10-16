package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import dev.sumin.skeleton.storage.KeyPrefixedPresignedStorage
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.ProjectRoutedStorage
import java.util.Optional
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
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
    fun s3Client(properties: S3StorageProperties): S3Client = newClient(properties)

    private fun newClient(properties: S3StorageProperties): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider(properties))
            .serviceConfiguration(s3Configuration(properties))

        properties.endpointOverride?.let { builder.endpointOverride(it) }

        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun s3Presigner(properties: S3StorageProperties): S3Presigner = newPresigner(properties)

    private fun newPresigner(properties: S3StorageProperties): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider(properties))
            .serviceConfiguration(s3Configuration(properties))

        // presign URL 은 클라이언트(브라우저)가 여는 주소이므로 서버→S3 주소와 다를 수 있다 (presign.endpoint-override 가 우선)
        (properties.presign.endpointOverride ?: properties.endpointOverride)?.let { builder.endpointOverride(it) }

        return builder.build()
    }

    // OPAQUE 는 crypto 가 있을 때 S3OpaquePublicUrlAutoConfiguration 이 먼저 등록한다. 여기까지 왔다면 crypto 가 클래스패스에 없다.
    @Bean
    @ConditionalOnMissingBean
    fun storagePublicUrlResolver(properties: S3StorageProperties): StoragePublicUrlResolver =
        when {
            properties.publicUrl.baseUrl.isBlank() -> StoragePublicUrlResolver.NONE
            properties.publicUrl.strategy == S3StorageProperties.PublicUrlStrategy.RAW ->
                BaseUrlStoragePublicUrlResolver(properties.publicUrl.baseUrl)
            properties.publicUrl.strategy == S3StorageProperties.PublicUrlStrategy.OPAQUE ->
                throw IllegalStateException(
                    "skeleton.storage-s3.public-url.strategy=OPAQUE needs the crypto module: " +
                        "add implementation(project(\":modules:crypto\")) and configure skeleton.crypto.keys.",
                )
            else -> error("Unsupported storage public URL strategy '${properties.publicUrl.strategy}'.")
        }

    @Bean
    @ConditionalOnMissingBean(PresignedStorage::class)
    @ConditionalOnProperty(prefix = "skeleton.storage-s3", name = ["bucket"])
    @Conditional(ProjectsNotConfigured::class)
    fun s3PresignedStorageService(
        properties: S3StorageProperties,
        s3Client: S3Client,
        s3Presigner: S3Presigner,
        storagePublicUrlResolver: StoragePublicUrlResolver,
    ): PresignedStorage =
        withKeyPrefix(
            S3PresignedStorageService(
                bucket = properties.bucket,
                s3Client = s3Client,
                presigner = s3Presigner,
                uploadPresignDuration = properties.presign.upload,
                downloadPresignDuration = properties.presign.download,
                multipartPartPresignDuration = properties.presign.multipartPart,
                publicUrlResolver = storagePublicUrlResolver,
            ),
            properties,
        )

    /** 프로젝트 모드에서 프로젝트마다 만든 S3 클라이언트 · 서명기를 앱이 끝날 때 닫는다 */
    @Bean
    @Conditional(ProjectsConfigured::class)
    @ConditionalOnMissingBean
    fun projectS3Resources(): ProjectS3Resources = ProjectS3Resources()

    /**
     * 프로젝트 모드: 프로젝트마다 자기 `storage-s3.*` (공통 위에 덮어)로 S3 클라이언트 · 서명기 · 저장소를 만들고, 부를 때 지금 프로젝트의 것으로 넘긴다.
     * 버킷을 정하지 않은 프로젝트는 저장소가 없다(쓰면 그 프로젝트 이름의 예외). 같은 버킷을 둘이 쓰면 `key-prefix` 가 서로 달라야 한다(기동 실패).
     * 공개 URL 은 RAW 만 — OPAQUE 는 프로젝트별 암호화 키(crypto)가 있어야 하는데 crypto 가 아직 프로젝트를 모른다.
     */
    @Bean
    @Conditional(ProjectsConfigured::class)
    @ConditionalOnMissingBean(PresignedStorage::class)
    fun projectS3PresignedStorage(projects: ProjectRegistry, resources: ProjectS3Resources): PresignedStorage {
        val parts = projects.scopedFrom("skeleton.storage-s3", S3StorageProperties::class.java) { project, p ->
            if (p.bucket.isBlank()) return@scopedFrom Optional.empty<PresignedStorage>()
            check(p.publicUrl.strategy == S3StorageProperties.PublicUrlStrategy.RAW || p.publicUrl.baseUrl.isBlank()) {
                "project '${project.id}': skeleton.storage-s3.public-url.strategy=OPAQUE is not available in project mode yet (it needs per-project crypto keys); use RAW"
            }
            val client = newClient(p).also(resources::add)
            val presigner = newPresigner(p).also(resources::add)
            val resolver = if (p.publicUrl.baseUrl.isBlank()) StoragePublicUrlResolver.NONE else BaseUrlStoragePublicUrlResolver(p.publicUrl.baseUrl)
            Optional.of(
                withKeyPrefix(
                    S3PresignedStorageService(p.bucket, client, presigner, p.presign.upload, p.presign.download, p.presign.multipartPart, resolver),
                    p,
                ),
            )
        }
        val active = projects.active.map { project ->
            try {
                project to projects.bind(project, "skeleton.storage-s3", S3StorageProperties::class.java)
            } catch (ex: RuntimeException) {
                throw IllegalStateException("Storage of project '${project.id}' cannot start: ${generateSequence<Throwable>(ex) { it.cause }.last().message}", ex)
            }
        }.filter { it.second.bucket.isNotBlank() }
        active.groupBy { (_, p) -> Triple(p.endpointOverride, p.bucket, p.keyPrefix.trim('/')) }.values.firstOrNull { it.size > 1 }?.let { same ->
            error(
                "Projects ${same.joinToString { "'${it.first.id}'" }} use the same bucket '${same.first().second.bucket}' with the same key prefix — " +
                    "give each its own bucket or its own skeleton.projects.<id>.config.storage-s3.key-prefix",
            )
        }
        active.forEach { (project, _) ->
            try {
                parts.of(project)
            } catch (ex: RuntimeException) {
                throw IllegalStateException("Storage of project '${project.id}' cannot start: ${generateSequence<Throwable>(ex) { it.cause }.last().message}", ex)
            }
        }
        return ProjectRoutedStorage(parts)
    }

    private fun withKeyPrefix(storage: PresignedStorage, properties: S3StorageProperties): PresignedStorage =
        if (properties.keyPrefix.isBlank()) storage else KeyPrefixedPresignedStorage(storage, properties.keyPrefix)

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

/** 프로젝트마다 만든 S3 클라이언트 · 서명기 — 앱이 끝날 때 닫는다 */
class ProjectS3Resources : org.springframework.beans.factory.DisposableBean {
    private val closeables = java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>()

    fun add(closeable: AutoCloseable) {
        closeables += closeable
    }

    override fun destroy() = closeables.forEach { runCatching { it.close() } }
}
