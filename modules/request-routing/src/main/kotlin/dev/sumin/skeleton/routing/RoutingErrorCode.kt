package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

enum class RoutingErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    MALFORMED("ROUTING.MALFORMED", HttpStatus.BAD_REQUEST, "Malformed route"),
    UNKNOWN_TARGET("ROUTING.UNKNOWN_TARGET", HttpStatus.BAD_REQUEST, "Unknown route target"),
    LOCAL_CLOSED("ROUTING.LOCAL_CLOSED", HttpStatus.FORBIDDEN, "Local route window is closed"),
    LOOP("ROUTING.LOOP", HttpStatus.LOOP_DETECTED, "Route loop detected"),
    TARGET_UNREACHABLE("ROUTING.TARGET_UNREACHABLE", HttpStatus.BAD_GATEWAY, "Route target unreachable"),
}
