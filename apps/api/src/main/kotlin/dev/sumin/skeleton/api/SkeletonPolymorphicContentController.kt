package dev.sumin.skeleton.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.enumcode.StringCodeEnum
import dev.sumin.skeleton.common.openapi.CreatedOperation
import dev.sumin.skeleton.json.JsonPayloadDefinition
import dev.sumin.skeleton.json.JsonPayloadRegistry
import dev.sumin.skeleton.json.VersionedJsonDocument
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import kotlin.reflect.KClass
import org.springframework.http.MediaType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

enum class SkeletonContentType(
    override val code: String,
    override val label: String,
    override val description: String? = null,
) : StringCodeEnum {
    VIDEO("VIDEO", "Video", "Moving image content with duration and dimensions."),
    IMAGE("IMAGE", "Image", "Still image content with dimensions."),
    TTS("TTS", "Text to speech", "Generated speech audio content."),
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = SkeletonVideoContentCreateSpec::class, name = "VIDEO"),
    JsonSubTypes.Type(value = SkeletonImageContentCreateSpec::class, name = "IMAGE"),
    JsonSubTypes.Type(value = SkeletonTtsContentCreateSpec::class, name = "TTS"),
)
@Schema(
    description = "Type-specific content create spec.",
    discriminatorProperty = "type",
    requiredProperties = ["type"],
    discriminatorMapping = [
        DiscriminatorMapping(value = "VIDEO", schema = SkeletonVideoContentCreateSpec::class),
        DiscriminatorMapping(value = "IMAGE", schema = SkeletonImageContentCreateSpec::class),
        DiscriminatorMapping(value = "TTS", schema = SkeletonTtsContentCreateSpec::class),
    ],
    oneOf = [
        SkeletonVideoContentCreateSpec::class,
        SkeletonImageContentCreateSpec::class,
        SkeletonTtsContentCreateSpec::class,
    ],
)
sealed interface SkeletonContentCreateSpec {
    val type: SkeletonContentType
}

@JsonTypeName("VIDEO")
data class SkeletonVideoContentCreateSpec(
    override val type: SkeletonContentType = SkeletonContentType.VIDEO,
    @field:Min(1)
    val durationMs: Long,
    @field:Min(1)
    val width: Int,
    @field:Min(1)
    val height: Int,
    val codec: String? = null,
) : SkeletonContentCreateSpec

@JsonTypeName("IMAGE")
data class SkeletonImageContentCreateSpec(
    override val type: SkeletonContentType = SkeletonContentType.IMAGE,
    @field:Min(1)
    val width: Int,
    @field:Min(1)
    val height: Int,
    val format: String? = null,
) : SkeletonContentCreateSpec

@JsonTypeName("TTS")
data class SkeletonTtsContentCreateSpec(
    override val type: SkeletonContentType = SkeletonContentType.TTS,
    @field:NotBlank
    val text: String,
    @field:NotBlank
    val voice: String,
    val speed: Double? = null,
) : SkeletonContentCreateSpec

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = SkeletonVideoContentResponseSpec::class, name = "VIDEO"),
    JsonSubTypes.Type(value = SkeletonImageContentResponseSpec::class, name = "IMAGE"),
    JsonSubTypes.Type(value = SkeletonTtsContentResponseSpec::class, name = "TTS"),
)
@Schema(
    description = "Type-specific content response spec.",
    discriminatorProperty = "type",
    requiredProperties = ["type"],
    discriminatorMapping = [
        DiscriminatorMapping(value = "VIDEO", schema = SkeletonVideoContentResponseSpec::class),
        DiscriminatorMapping(value = "IMAGE", schema = SkeletonImageContentResponseSpec::class),
        DiscriminatorMapping(value = "TTS", schema = SkeletonTtsContentResponseSpec::class),
    ],
    oneOf = [
        SkeletonVideoContentResponseSpec::class,
        SkeletonImageContentResponseSpec::class,
        SkeletonTtsContentResponseSpec::class,
    ],
)
sealed interface SkeletonContentResponseSpec {
    val type: SkeletonContentType
}

@JsonTypeName("VIDEO")
data class SkeletonVideoContentResponseSpec(
    override val type: SkeletonContentType = SkeletonContentType.VIDEO,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val streamUrl: String,
    val thumbnailUrl: String? = null,
) : SkeletonContentResponseSpec

@JsonTypeName("IMAGE")
data class SkeletonImageContentResponseSpec(
    override val type: SkeletonContentType = SkeletonContentType.IMAGE,
    val width: Int,
    val height: Int,
    val imageUrl: String,
    val thumbnailUrl: String? = null,
) : SkeletonContentResponseSpec

@JsonTypeName("TTS")
data class SkeletonTtsContentResponseSpec(
    override val type: SkeletonContentType = SkeletonContentType.TTS,
    val voice: String,
    val durationMs: Long,
    val audioUrl: String,
) : SkeletonContentResponseSpec

data class SkeletonContentCreateRequest(
    @field:NotBlank
    val name: String,
    @field:Valid
    @field:Schema(
        description = "Type-specific create payload. The spec.type discriminator selects the concrete schema.",
        discriminatorProperty = "type",
        requiredProperties = ["type"],
        discriminatorMapping = [
            DiscriminatorMapping(value = "VIDEO", schema = SkeletonVideoContentCreateSpec::class),
            DiscriminatorMapping(value = "IMAGE", schema = SkeletonImageContentCreateSpec::class),
            DiscriminatorMapping(value = "TTS", schema = SkeletonTtsContentCreateSpec::class),
        ],
        oneOf = [
            SkeletonVideoContentCreateSpec::class,
            SkeletonImageContentCreateSpec::class,
            SkeletonTtsContentCreateSpec::class,
        ],
    )
    val spec: SkeletonContentCreateSpec,
)

data class SkeletonContentResponse(
    val id: String,
    val type: SkeletonContentType,
    val name: String,
    @field:Schema(
        description = "Type-specific response payload. The spec.type discriminator selects the concrete schema.",
        discriminatorProperty = "type",
        requiredProperties = ["type"],
        discriminatorMapping = [
            DiscriminatorMapping(value = "VIDEO", schema = SkeletonVideoContentResponseSpec::class),
            DiscriminatorMapping(value = "IMAGE", schema = SkeletonImageContentResponseSpec::class),
            DiscriminatorMapping(value = "TTS", schema = SkeletonTtsContentResponseSpec::class),
        ],
        oneOf = [
            SkeletonVideoContentResponseSpec::class,
            SkeletonImageContentResponseSpec::class,
            SkeletonTtsContentResponseSpec::class,
        ],
    )
    val spec: SkeletonContentResponseSpec,
    val specDocument: VersionedJsonDocument,
)

object SkeletonContentResponseSpecPayload : JsonPayloadDefinition<SkeletonContentResponseSpec> {
    override val type: String = "skeleton.content-response-spec"
    override val currentVersion: Int = 1
    override val payloadClass: KClass<SkeletonContentResponseSpec> = SkeletonContentResponseSpec::class
}

@Configuration
class SkeletonPolymorphicContentJsonConfiguration {
    @Bean
    fun skeletonContentResponseSpecPayloadDefinition(): JsonPayloadDefinition<SkeletonContentResponseSpec> =
        SkeletonContentResponseSpecPayload
}

@RestController
@RequestMapping("/api/v1/skeleton/polymorphic/contents")
class SkeletonPolymorphicContentController(
    private val jsonPayloadRegistry: JsonPayloadRegistry,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @CreatedOperation
    fun create(
        @Valid @RequestBody request: SkeletonContentCreateRequest,
    ) = toResponse(request)
        .let { response ->
            Response.created(
                location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(response.id)
                    .toUri(),
                value = response,
            )
        }

    private fun toResponse(request: SkeletonContentCreateRequest): SkeletonContentResponse =
        when (val spec = request.spec) {
            is SkeletonVideoContentCreateSpec ->
                contentResponse(
                    id = "sample-video",
                    type = spec.type,
                    name = request.name,
                    spec = SkeletonVideoContentResponseSpec(
                        durationMs = spec.durationMs,
                        width = spec.width,
                        height = spec.height,
                        streamUrl = "https://cdn.example.test/sample-video.mp4",
                        thumbnailUrl = "https://cdn.example.test/sample-video.jpg",
                    ),
                )
            is SkeletonImageContentCreateSpec ->
                contentResponse(
                    id = "sample-image",
                    type = spec.type,
                    name = request.name,
                    spec = SkeletonImageContentResponseSpec(
                        width = spec.width,
                        height = spec.height,
                        imageUrl = "https://cdn.example.test/sample-image.png",
                        thumbnailUrl = "https://cdn.example.test/sample-image-thumb.png",
                    ),
                )
            is SkeletonTtsContentCreateSpec ->
                contentResponse(
                    id = "sample-tts",
                    type = spec.type,
                    name = request.name,
                    spec = SkeletonTtsContentResponseSpec(
                        voice = spec.voice,
                        durationMs = estimateTtsDuration(spec.text),
                        audioUrl = "https://cdn.example.test/sample-tts.mp3",
                    ),
                )
        }

    private fun contentResponse(
        id: String,
        type: SkeletonContentType,
        name: String,
        spec: SkeletonContentResponseSpec,
    ): SkeletonContentResponse =
        SkeletonContentResponse(
            id = id,
            type = type,
            name = name,
            spec = spec,
            specDocument = jsonPayloadRegistry.writeLatest(
                definition = SkeletonContentResponseSpecPayload,
                payload = spec,
                metadata = mapOf(
                    "contentId" to id,
                    "contentType" to type.code,
                ),
            ),
        )

    private fun estimateTtsDuration(text: String): Long =
        ((text.length + 1) * 100L).coerceAtLeast(1000L)
}
