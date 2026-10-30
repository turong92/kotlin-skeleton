package dev.sumin.skeleton.workspace

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   workspace:
 *     http:
 *       enabled: true                  # false 면 사용자 경로(/api/v1/workspaces …)를 닫는다. 경계(CurrentWorkspace)는 그대로 쓸 수 있다
 *       base-path: /api/v1/workspaces
 * ```
 */
@ConfigurationProperties("skeleton.workspace")
data class WorkspaceProperties(
    val http: Http = Http(),
) {
    data class Http(
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/workspaces",
    )
}
