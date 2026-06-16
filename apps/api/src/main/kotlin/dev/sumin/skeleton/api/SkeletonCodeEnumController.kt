package dev.sumin.skeleton.api

import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.enumcode.CodeEnumDescriptor
import dev.sumin.skeleton.common.enumcode.CodeEnumResolver
import dev.sumin.skeleton.common.enumcode.IntCodeEnum
import dev.sumin.skeleton.common.enumcode.StringCodeEnum
import dev.sumin.skeleton.common.enumcode.toDescriptor
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

enum class SkeletonSampleStatus(
    override val code: Int,
    override val label: String,
    override val description: String? = null,
) : IntCodeEnum {
    CREATED(10, "Created", "Created but unpaid."),
    PAID(20, "Paid"),
    CANCELED(90, "Canceled"),
}

enum class SkeletonSampleTone(
    override val code: String,
    override val label: String,
    override val description: String? = null,
) : StringCodeEnum {
    CALM("calm", "Calm", "Low urgency."),
    LOUD("loud", "Loud"),
}

data class SkeletonCodeEnumRequest(
    val status: SkeletonSampleStatus,
)

data class SkeletonCodeEnumResponse(
    val status: SkeletonSampleStatus,
    val statusInfo: CodeEnumDescriptor<Int>,
    val allStatuses: List<CodeEnumDescriptor<Any>>,
)

data class SkeletonToneResponse(
    val tone: SkeletonSampleTone,
    val toneInfo: CodeEnumDescriptor<String>,
)

@RestController
@RequestMapping("/api/v1/skeleton/enums")
class SkeletonCodeEnumController {
    @GetMapping("/status")
    fun status(
        @RequestParam status: SkeletonSampleStatus,
    ) = Response.ok(statusResponse(status))

    @PostMapping(
        "/status",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun statusFromBody(
        @Valid @RequestBody request: SkeletonCodeEnumRequest,
    ) = Response.ok(statusResponse(request.status))

    @GetMapping("/tone/{tone}")
    fun tone(
        @PathVariable tone: SkeletonSampleTone,
    ) = Response.ok(
        SkeletonToneResponse(
            tone = tone,
            toneInfo = tone.toDescriptor(),
        ),
    )

    private fun statusResponse(status: SkeletonSampleStatus): SkeletonCodeEnumResponse =
        SkeletonCodeEnumResponse(
            status = status,
            statusInfo = status.toDescriptor(),
            allStatuses = CodeEnumResolver.descriptors(SkeletonSampleStatus::class),
        )
}
