package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.AccountPurgeService
import dev.sumin.skeleton.account.AdminService
import dev.sumin.skeleton.account.authz.AccountPermissions
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.PaginationMeta
import dev.sumin.skeleton.common.Response
import io.swagger.v3.oas.annotations.Operation
import org.springdoc.core.annotations.ParameterObject
import org.springframework.web.bind.annotation.ModelAttribute
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 운영자 계정 관리 — `skeleton.account.admin.enabled=true` 일 때만, 엔드포인트마다 권한 하나(`account.read` · `account.manage` · `account.roles`). [AccountWebAutoConfiguration] 이 등록한다. */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@Tag(name = "Account admin")
class AdminAccountController(private val callers: AccountCallers, private val admin: AdminService, private val purge: AccountPurgeService) {
    @Operation(summary = "Search accounts by email fragment, exact login ID (only when login-id is enabled) and status")
    @GetMapping
    fun search(
        authentication: Authentication?,
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) loginId: String?,
        @RequestParam(required = false) status: AccountStatus?,
        @Valid @ParameterObject @ModelAttribute page: PageQuery,
    ): PageResponse<AdminAccountResponse> {
        callers.requirePermission(authentication, AccountPermissions.READ)
        val result = admin.search(email, status, page.page, page.size, loginId)
        return Response.ok(result.items.map { it.toAdmin() }, page.toPagination(result.total))
    }

    @Operation(summary = "One account")
    @GetMapping("/{id}")
    fun get(authentication: Authentication?, @PathVariable id: String): DataResponse<AdminAccountResponse> {
        callers.requirePermission(authentication, AccountPermissions.READ)
        return Response.ok(admin.get(id).toAdmin())
    }

    @Operation(summary = "Suspend (signs the account out of every session)")
    @PostMapping("/{id}/suspend")
    fun suspend(authentication: Authentication?, @PathVariable id: String, @Valid @RequestBody(required = false) request: SuspendRequest?): ResponseEntity<Void> {
        val caller = manager(authentication, id)
        admin.suspend(caller.accountId, id, request?.reason)
        return Response.noContent()
    }

    @Operation(summary = "Unsuspend")
    @PostMapping("/{id}/unsuspend")
    fun unsuspend(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val caller = manager(authentication, id)
        admin.unsuspend(caller.accountId, id)
        return Response.noContent()
    }

    @Operation(summary = "Undo a deletion inside the grace period")
    @PostMapping("/{id}/restore")
    fun restore(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val caller = manager(authentication, id)
        admin.restore(caller.accountId, id)
        return Response.noContent()
    }

    @Operation(
        summary = "Erase a SUSPENDED account at once (no grace): personal data goes, the row stays as ERASED, and a hash-only re-registration block is kept",
        description = "409 ACCOUNT.NOT_SUSPENDED for any other status (also when it changed meanwhile), 410 ACCOUNT.ERASED when it is already erased, 409 ACCOUNT.SELF_ACTION_FORBIDDEN for yourself, " +
            "503 ACCOUNT.ERASURE_RETRY when another module's erasure failed (the account is left as it was - call again).",
    )
    @PostMapping("/{id}/erase")
    fun erase(authentication: Authentication?, @PathVariable id: String, @Valid @RequestBody(required = false) request: AdminEraseRequest?): ResponseEntity<Void> {
        // 지워지지 않았다면(그 사이 상태가 바뀜) 204 로 거짓말하지 않는다
        if (!purge.eraseSuspended(manager(authentication, id).accountId, id, request?.reason)) throw AccountException(AccountErrorCode.NOT_SUSPENDED)
        return Response.noContent()
    }

    @Operation(summary = "Re-registration blocks left by administrator erasures (hash only; newest first)")
    @GetMapping("/blocks")
    fun blocks(authentication: Authentication?, @Valid @ParameterObject @ModelAttribute page: PageQuery): PageResponse<AccountBlockResponse> {
        callers.requirePermission(authentication, AccountPermissions.READ)
        val result = admin.listBlocks(page.page, page.size)
        return Response.ok(result.items.map { AccountBlockResponse(it.id, it.kind, it.reason, it.createdAt, it.expiresAt, it.createdBy, it.accountId) }, page.toPagination(result.total))
    }

    @Operation(summary = "Lift a re-registration block (404 when it is gone)")
    @DeleteMapping("/blocks/{blockId}")
    fun removeBlock(authentication: Authentication?, @PathVariable blockId: Long): ResponseEntity<Void> {
        admin.removeBlock(callers.requirePermission(authentication, AccountPermissions.MANAGE).accountId, blockId)
        return Response.noContent()
    }

    @Operation(summary = "Roles that can be granted, with their permissions (full authority is [\"*\"])", description = "A role above the caller's own permissions is listed too; granting it is 403 ACCOUNT.ROLE_ESCALATION")
    @GetMapping("/assignable-roles")
    fun assignableRoles(authentication: Authentication?): ListResponse<AssignableRoleResponse> {
        callers.requirePermission(authentication, AccountPermissions.ROLES)
        return Response.ok(admin.assignableRoles().map { (name, permissions) -> AssignableRoleResponse(name, permissions) })
    }

    @Operation(summary = "Grant a role", description = "400 ACCOUNT.ROLE_UNKNOWN for an undefined role, 403 ACCOUNT.ROLE_ESCALATION when the role holds permissions the caller does not")
    @PutMapping("/{id}/roles/{role}")
    fun grant(authentication: Authentication?, @PathVariable id: String, @PathVariable role: String): ResponseEntity<Void> {
        val caller = callers.requirePermission(authentication, AccountPermissions.ROLES)
        admin.grantRole(caller.accountId, id, role, callers.permissionsOf(caller))
        return Response.noContent()
    }

    @Operation(summary = "Revoke a role (409 ACCOUNT.LAST_ADMIN for the last administrator, 403 ACCOUNT.ROLE_ESCALATION for a role above the caller)")
    @DeleteMapping("/{id}/roles/{role}")
    fun revoke(authentication: Authentication?, @PathVariable id: String, @PathVariable role: String): ResponseEntity<Void> {
        val caller = callers.requirePermission(authentication, AccountPermissions.ROLES)
        admin.revokeRole(caller.accountId, id, role, callers.permissionsOf(caller))
        return Response.noContent()
    }

    /** `account.manage` + 대상 계정이 호출자 권한 안에 있는가 ([AdminService.requireCanManage]) */
    private fun manager(authentication: Authentication?, targetId: String): AccountCaller {
        val caller = callers.requirePermission(authentication, AccountPermissions.MANAGE)
        admin.requireCanManage(targetId, callers.permissionsOf(caller))
        return caller
    }

    private fun Account.toAdmin() = AdminAccountResponse(id, email, status.name, roles, displayName, visibleTag, createdAt, lastLoginAt, suspendedReason, purgeAfter, erasedAt, if (admin.loginIdEnabled) java.util.Optional.ofNullable(loginId) else null)
}

/** `GET /admin/accounts/assignable-roles` 의 항목 — 전권 역할은 permissions `["*"]` */
data class AssignableRoleResponse(val name: String, val permissions: List<String>)
