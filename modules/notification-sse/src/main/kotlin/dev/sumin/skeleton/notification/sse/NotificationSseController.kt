package dev.sumin.skeleton.notification.sse

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.security.Principal
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@Tag(name = "Notifications")
class NotificationSseController(
    private val service: NotificationSseService,
) {
    @Operation(
        summary = "Subscribe to server notifications",
        description = "Opens a text/event-stream connection. Repeat the topic query parameter to filter notifications. " +
            "Notifications addressed to specific recipients reach only those recipients.",
    )
    @GetMapping(
        "/api/v1/notifications/sse",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun subscribe(
        principal: Principal?,
        @RequestParam(name = "topic", required = false) topics: List<String>?,
    ): SseEmitter =
        service.connect(topics ?: emptyList(), principal?.name)
}
