package dev.sumin.skeleton.notification.websocket

import java.security.Principal

fun interface NotificationWebSocketTokenVerifier {
    fun verify(token: String): Principal?
}

data class NotificationWebSocketPrincipal(
    private val principalName: String,
    val attributes: Map<String, Any?> = emptyMap(),
) : Principal {
    override fun getName(): String = principalName
}
