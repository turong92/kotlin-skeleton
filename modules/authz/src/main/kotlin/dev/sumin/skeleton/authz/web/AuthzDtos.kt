package dev.sumin.skeleton.authz.web

import dev.sumin.skeleton.authz.PermissionInfo
import dev.sumin.skeleton.authz.Role

data class CreateRoleRequest(val name: String? = null, val description: String? = null, val permissions: List<String>? = null)

data class UpdateRoleRequest(val description: String? = null, val permissions: List<String>? = null)

data class PermissionResponse(val key: String, val module: String)

data class RoleResponse(
    val name: String,
    val description: String?,
    val permissions: List<String>,
    val protected: Boolean,
    /** 역할을 가진 활성 계정 수 — 계정 모듈이 없으면 null */
    val holders: Long?,
)

fun PermissionInfo.toResponse() = PermissionResponse(key, module)

fun Role.toResponse() = RoleResponse(name, description, permissions, protected, holders)
