package dev.sumin.skeleton.authz.web

import dev.sumin.skeleton.authz.PermissionInfo
import dev.sumin.skeleton.authz.Role
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CreateRoleRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") val name: String? = null,
    @field:Size(max = 200) val description: String? = null,
    @field:NotNull @field:Size(max = 200) val permissions: List<String>? = null,
    @field:Size(max = 50) val includes: List<String>? = null,
)

data class UpdateRoleRequest(
    @field:Size(max = 200) val description: String? = null,
    @field:NotNull @field:Size(max = 200) val permissions: List<String>? = null,
    /** 빠지면 물려받는 역할을 그대로 둔다 — 위계를 모르는 옛 화면이 지우지 않게 */
    @field:Size(max = 50) val includes: List<String>? = null,
)

data class PermissionResponse(val key: String, val module: String)

data class RoleResponse(
    val name: String,
    val description: String?,
    /** 직접 가진 권한 */
    val permissions: List<String>,
    /** 물려받는 역할 */
    val includes: List<String>,
    /** 물려받은 것까지 펼친 권한 — 화면 표시용 */
    val effectivePermissions: List<String>,
    val protected: Boolean,
    /** 역할을 가진 활성 계정 수 — 계정 모듈이 없으면 null */
    val holders: Long?,
)

fun PermissionInfo.toResponse() = PermissionResponse(key, module)

fun Role.toResponse() = RoleResponse(name, description, permissions, includes, effectivePermissions, protected, holders)
