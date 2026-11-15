package dev.sumin.skeleton.routing.window

import dev.sumin.skeleton.routing.LocalWindow
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/** 길이 상한은 480분(8시간). 설정 `local.max-window` 가 더 짧으면 그쪽이 이긴다 */
data class OpenLocalWindowRequest(
    @field:Min(1) @field:Max(480) val minutes: Int,
    @field:NotBlank @field:Size(max = 200) val reason: String,
)

data class LocalWindowResponse(
    val id: Long,
    val openedBy: String,
    val reason: String,
    val openedAt: Instant,
    val openUntil: Instant,
) {
    companion object {
        fun of(window: LocalWindow) = LocalWindowResponse(window.id, window.openedBy, window.reason, window.openedAt, window.openUntil)
    }
}

data class LocalWindowStatusResponse(val open: Boolean, val window: LocalWindowResponse?)
