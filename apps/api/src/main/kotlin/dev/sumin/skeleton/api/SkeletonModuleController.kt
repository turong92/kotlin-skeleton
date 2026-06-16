package dev.sumin.skeleton.api

import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.social.oauth.OAuthSocialLoginService
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.event.kafka.KafkaEventPublisher
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.notification.sse.NotificationSseService
import dev.sumin.skeleton.notification.slack.SlackAlertSender
import dev.sumin.skeleton.notification.websocket.NotificationWebSocketBridge
import dev.sumin.skeleton.payment.PaymentService
import dev.sumin.skeleton.payment.stripe.StripePaymentProvider
import dev.sumin.skeleton.payment.toss.TossPaymentProvider
import dev.sumin.skeleton.persistence.jdbc.JdbcAuditBeforeConvertCallback
import dev.sumin.skeleton.persistence.jpa.JpaPartialUpdateExecutor
import dev.sumin.skeleton.redis.cache.NamedRedisCacheRegistry
import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import dev.sumin.skeleton.redis.lock.DistributedLockExecutor
import dev.sumin.skeleton.scheduler.SkeletonScheduledTaskRegistrar
import dev.sumin.skeleton.storage.StorageFileCandidate
import dev.sumin.skeleton.storage.StorageFileValidator
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import dev.sumin.skeleton.storage.s3.S3PresignedStorageService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

enum class SkeletonModuleStatus {
    ACTIVE,
    DISABLED,
    MISSING,
}

data class SkeletonModuleResponse(
    val id: String,
    val group: String,
    val status: SkeletonModuleStatus,
    val configPrefix: String,
    val requiredInfrastructure: List<String> = emptyList(),
    val beans: List<String> = emptyList(),
    val note: String? = null,
)

data class SkeletonRedisKeyResponse(
    val value: String,
    val key: String,
)

data class SkeletonStorageValidationRequest(
    @field:NotBlank
    val fileName: String,
    val contentType: String? = null,
    @field:PositiveOrZero
    val sizeBytes: Long,
)

data class SkeletonStorageValidationResponse(
    val valid: Boolean,
    val errors: List<SkeletonStorageValidationErrorResponse>,
)

data class SkeletonStorageValidationErrorResponse(
    val code: String,
    val message: String,
)

data class SkeletonStoragePublicUrlResponse(
    val key: String,
    val publicUrl: String?,
    val available: Boolean,
)

data class SkeletonNotificationPublishRequest(
    @field:NotBlank
    val topic: String,
    @field:NotBlank
    val type: String,
    val severity: NotificationSeverity = NotificationSeverity.INFO,
    val title: String? = null,
    val message: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class SkeletonNotificationPublishResponse(
    val eventId: String,
    val topic: String,
    val type: String,
    val deliveredSubscribers: Int,
)

/**
 * Module composition probes for this executable sample app.
 * Real services can delete this controller after choosing their module set.
 */
@Validated
@RestController
@RequestMapping("/api/v1/skeleton")
class SkeletonModuleController(
    private val environment: Environment,
    private val beanFactory: ListableBeanFactory,
    private val redisKeyPrefixer: ObjectProvider<RedisKeyPrefixer>,
    private val storageFileValidator: ObjectProvider<StorageFileValidator>,
    private val storagePublicUrlResolver: ObjectProvider<StoragePublicUrlResolver>,
    private val notificationPublisher: ObjectProvider<NotificationPublisher>,
) {
    @GetMapping("/modules")
    fun modules() =
        Response.ok(values = moduleCatalog())

    @GetMapping("/redis/key")
    fun redisKey(
        @RequestParam value: String,
    ) = Response.ok(
        SkeletonRedisKeyResponse(
            value = value,
            key = redisKeyPrefixer.getObject().key(value),
        ),
    )

    @PostMapping(
        "/storage/validate",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun validateStorageCandidate(
        @Valid @RequestBody request: SkeletonStorageValidationRequest,
    ) = storageFileValidator.getObject()
        .validate(
            StorageFileCandidate(
                fileName = request.fileName,
                contentType = request.contentType,
                sizeBytes = request.sizeBytes,
            ),
        )
        .let { result ->
            Response.ok(
                SkeletonStorageValidationResponse(
                    valid = result.valid,
                    errors = result.errors.map { error ->
                        SkeletonStorageValidationErrorResponse(
                            code = error.code.name,
                            message = error.message,
                        )
                    },
                ),
            )
        }

    @GetMapping("/storage/public-url")
    fun storagePublicUrl(
        @RequestParam key: String,
    ) = storagePublicUrlResolver.getIfAvailable { StoragePublicUrlResolver.NONE }
        .publicUrl(ObjectKey(key))
        .let { publicUrl ->
            Response.ok(
                SkeletonStoragePublicUrlResponse(
                    key = key,
                    publicUrl = publicUrl?.toString(),
                    available = publicUrl != null,
                ),
            )
        }

    @PostMapping(
        "/notifications",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun publishNotification(
        @Valid @RequestBody request: SkeletonNotificationPublishRequest,
    ) = NotificationEvent(
        topic = request.topic,
        type = request.type,
        severity = request.severity,
        title = request.title,
        message = request.message,
        payload = request.payload,
    ).let { event ->
        val result = notificationPublisher.getObject().publish(event)
        Response.ok(
            SkeletonNotificationPublishResponse(
                eventId = result.eventId,
                topic = event.topic,
                type = event.type,
                deliveredSubscribers = result.deliveredSubscribers,
            ),
        )
    }

    private fun moduleCatalog(): List<SkeletonModuleResponse> =
        listOf(
            module(
                id = "platform",
                group = "foundation",
                status = SkeletonModuleStatus.ACTIVE,
                configPrefix = "skeleton.*",
                beans = beanNames("dev.sumin.skeleton.common.TraceIdFilter"),
                note = "Response envelopes, errors, trace context, web policy, OpenAPI, and outbound HTTP.",
            ),
            module(
                id = "json",
                group = "foundation",
                status = activeWhenBeanPresent(JsonCodec::class.java),
                configPrefix = "n/a",
                beans = beanNames(JsonCodec::class.java),
                note = "JsonDocument, versioned JSON envelopes, migrations, DB converters, REST and OpenAPI schemas.",
            ),
            module(
                id = "auth",
                group = "identity",
                status = activeWhenBeanPresent(JwtTokenService::class.java),
                configPrefix = "skeleton.auth",
                beans = beanNames(JwtTokenService::class.java),
            ),
            module(
                id = "auth-social",
                group = "identity",
                status = activeWhenBeanPresent(OAuthSocialLoginService::class.java),
                configPrefix = "skeleton.auth-social",
                beans = beanNames(OAuthSocialLoginService::class.java),
            ),
            providerModule(
                id = "auth-social-google",
                group = "identity",
                configPrefix = "skeleton.auth-social.providers.google",
                enabledProperty = "skeleton.auth-social.providers.google.enabled",
                beanName = "googleOAuthProvider",
            ),
            providerModule(
                id = "auth-social-kakao",
                group = "identity",
                configPrefix = "skeleton.auth-social.providers.kakao",
                enabledProperty = "skeleton.auth-social.providers.kakao.enabled",
                beanName = "kakaoOAuthProvider",
            ),
            providerModule(
                id = "auth-social-naver",
                group = "identity",
                configPrefix = "skeleton.auth-social.providers.naver",
                enabledProperty = "skeleton.auth-social.providers.naver.enabled",
                beanName = "naverOAuthProvider",
            ),
            module(
                id = "redis-core",
                group = "redis",
                status = activeWhenBeanPresent(RedisKeyPrefixer::class.java),
                configPrefix = "skeleton.redis",
                requiredInfrastructure = listOf("Redis when callers execute Redis commands"),
                beans = beanNames(RedisKeyPrefixer::class.java),
                note = "Core connection/template beans do not ping Redis during startup.",
            ),
            toggleModule(
                id = "redis-lock",
                group = "redis",
                configPrefix = "skeleton.redis-lock",
                enabledProperty = "skeleton.redis-lock.enabled",
                defaultEnabled = false,
                activeBeanType = DistributedLockExecutor::class.java,
                requiredInfrastructure = listOf("Redis at startup when enabled"),
            ),
            toggleModule(
                id = "redis-cache",
                group = "redis",
                configPrefix = "skeleton.redis-cache",
                enabledProperty = "skeleton.redis-cache.enabled",
                defaultEnabled = false,
                activeBeanType = NamedRedisCacheRegistry::class.java,
                requiredInfrastructure = listOf("Redis when enabled unless no-op fallback is configured"),
            ),
            toggleModule(
                id = "redis-rate-limit",
                group = "redis",
                configPrefix = "skeleton.redis-rate-limit",
                enabledProperty = "skeleton.redis-rate-limit.enabled",
                defaultEnabled = false,
                activeBeanType = RateLimitStore::class.java,
                requiredInfrastructure = listOf("Redis when enabled"),
            ),
            module(
                id = "persistence-jdbc",
                group = "persistence",
                status = activeWhenBeanPresent(JdbcAuditBeforeConvertCallback::class.java),
                configPrefix = "n/a",
                beans = beanNames(JdbcAuditBeforeConvertCallback::class.java),
                note = "Spring Data JDBC audit timestamp callback.",
            ),
            module(
                id = "persistence-jpa",
                group = "persistence",
                status = activeWhenBeanPresent(JpaPartialUpdateExecutor::class.java),
                configPrefix = "n/a",
                beans = beanNames(JpaPartialUpdateExecutor::class.java),
                note = "JPA audit, optimistic locking base, partial updates, and fetch graph hints.",
            ),
            module(
                id = "notification",
                group = "notification",
                status = activeWhenBeanPresent(NotificationPublisher::class.java),
                configPrefix = "skeleton.notification",
                beans = beanNames(NotificationPublisher::class.java),
            ),
            toggleModule(
                id = "notification-sse",
                group = "notification",
                configPrefix = "skeleton.notification.sse",
                enabledProperty = "skeleton.notification.sse.enabled",
                defaultEnabled = true,
                activeBeanType = NotificationSseService::class.java,
            ),
            toggleModule(
                id = "notification-slack",
                group = "notification",
                configPrefix = "skeleton.notification.slack",
                enabledProperty = "skeleton.notification.slack.enabled",
                defaultEnabled = false,
                activeBeanType = SlackAlertSender::class.java,
                requiredInfrastructure = listOf("Slack webhook when enabled"),
            ),
            toggleModule(
                id = "notification-websocket",
                group = "notification",
                configPrefix = "skeleton.notification-websocket",
                enabledProperty = "skeleton.notification-websocket.enabled",
                defaultEnabled = false,
                activeBeanType = NotificationWebSocketBridge::class.java,
            ),
            module(
                id = "storage",
                group = "storage",
                status = activeWhenBeanPresent(StorageFileValidator::class.java),
                configPrefix = "skeleton.storage",
                beans = beanNames(StorageFileValidator::class.java),
            ),
            toggleModule(
                id = "storage-s3",
                group = "storage",
                configPrefix = "skeleton.storage-s3",
                enabledProperty = "skeleton.storage-s3.enabled",
                defaultEnabled = false,
                activeBeanType = S3PresignedStorageService::class.java,
                requiredInfrastructure = listOf("AWS credentials and S3 bucket when enabled"),
                note = "Direct object upload/copy/move/delete/list, presigned URLs, multipart uploads, and public URL resolving.",
            ),
            module(
                id = "payment",
                group = "payment",
                status = activeWhenBeanPresent(PaymentService::class.java),
                configPrefix = "skeleton.payment",
                beans = beanNames(PaymentService::class.java),
            ),
            toggleModule(
                id = "payment-toss",
                group = "payment",
                configPrefix = "skeleton.payment-toss",
                enabledProperty = "skeleton.payment-toss.enabled",
                defaultEnabled = false,
                activeBeanType = TossPaymentProvider::class.java,
                requiredInfrastructure = listOf("Toss secret key when enabled"),
            ),
            toggleModule(
                id = "payment-stripe",
                group = "payment",
                configPrefix = "skeleton.payment-stripe",
                enabledProperty = "skeleton.payment-stripe.enabled",
                defaultEnabled = false,
                activeBeanType = StripePaymentProvider::class.java,
                requiredInfrastructure = listOf("Stripe secret key when enabled"),
            ),
            module(
                id = "event-kafka",
                group = "event",
                status = activeWhenBeanPresent(KafkaEventPublisher::class.java),
                configPrefix = "skeleton.event-kafka",
                requiredInfrastructure = listOf("KafkaOperations bean when skeleton.event-kafka.enabled=true"),
                beans = beanNames(KafkaEventPublisher::class.java),
                note = "Disabled publishing uses a logging sender so app composition still starts.",
            ),
            toggleModule(
                id = "scheduler",
                group = "scheduler",
                configPrefix = "skeleton.scheduler",
                enabledProperty = "skeleton.scheduler.enabled",
                defaultEnabled = false,
                activeBeanType = SkeletonScheduledTaskRegistrar::class.java,
            ),
        ).sortedWith(compareBy<SkeletonModuleResponse> { it.group }.thenBy { it.id })

    private fun providerModule(
        id: String,
        group: String,
        configPrefix: String,
        enabledProperty: String,
        beanName: String,
    ): SkeletonModuleResponse {
        val enabled = booleanProperty(enabledProperty, defaultValue = false)
        val beans = if (beanFactory.containsBean(beanName)) listOf(beanName) else emptyList()
        return module(
            id = id,
            group = group,
            status = if (enabled && beans.isNotEmpty()) SkeletonModuleStatus.ACTIVE else SkeletonModuleStatus.DISABLED,
            configPrefix = configPrefix,
            requiredInfrastructure = listOf("OAuth client credentials when enabled"),
            beans = beans,
        )
    }

    private fun <T> toggleModule(
        id: String,
        group: String,
        configPrefix: String,
        enabledProperty: String,
        defaultEnabled: Boolean,
        activeBeanType: Class<T>,
        requiredInfrastructure: List<String> = emptyList(),
        note: String? = null,
    ): SkeletonModuleResponse {
        val enabled = booleanProperty(enabledProperty, defaultValue = defaultEnabled)
        val beans = beanNames(activeBeanType)
        val status = when {
            !enabled -> SkeletonModuleStatus.DISABLED
            beans.isNotEmpty() -> SkeletonModuleStatus.ACTIVE
            else -> SkeletonModuleStatus.MISSING
        }
        return module(
            id = id,
            group = group,
            status = status,
            configPrefix = configPrefix,
            requiredInfrastructure = requiredInfrastructure,
            beans = beans,
            note = note,
        )
    }

    private fun <T> activeWhenBeanPresent(type: Class<T>): SkeletonModuleStatus =
        if (beanNames(type).isEmpty()) SkeletonModuleStatus.MISSING else SkeletonModuleStatus.ACTIVE

    private fun module(
        id: String,
        group: String,
        status: SkeletonModuleStatus,
        configPrefix: String,
        requiredInfrastructure: List<String> = emptyList(),
        beans: List<String> = emptyList(),
        note: String? = null,
    ): SkeletonModuleResponse =
        SkeletonModuleResponse(
            id = id,
            group = group,
            status = status,
            configPrefix = configPrefix,
            requiredInfrastructure = requiredInfrastructure,
            beans = beans,
            note = note,
        )

    private fun beanNames(typeName: String): List<String> =
        runCatching {
            Class.forName(typeName).let { type -> beanFactory.getBeanNamesForType(type).toList().sorted() }
        }.getOrDefault(emptyList())

    private fun <T> beanNames(type: Class<T>): List<String> =
        beanFactory.getBeanNamesForType(type).toList().sorted()

    private fun booleanProperty(
        name: String,
        defaultValue: Boolean,
    ): Boolean =
        environment.getProperty(name)?.toBooleanStrictOrNull() ?: defaultValue
}
