package dev.sumin.skeleton.appcontrol.web

import dev.sumin.skeleton.appcontrol.AppControl
import dev.sumin.skeleton.appcontrol.AppControlPermissions
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 클라이언트가 시작할 때 · 앞으로 돌아올 때 읽는 설정 (`skeleton.app-control.config.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 익명 · 점검 중에도 열려 있다 */
@RestController
@RequestMapping("\${skeleton.app-control.config.base-path:/api/v1/app/config}")
class AppConfigController(private val control: AppControl, private val time: TimeProvider) {
    @GetMapping
    fun config(): DataResponse<AppConfigResponse> = Response.ok(control.snapshot().toConfigResponse(time.now()))
}

/**
 * 운영자 경로(`skeleton.app-control.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 보기(`app-control.read`)와 바꾸기(`app-control.write`)를
 * 매 호출 판정한다 - 익명 401 · 권한 없음 403. 읽기는 이 인스턴스의 캐시 값이다(다른 인스턴스에서 바꾼 것은 ttl 안에 보인다).
 */
@RestController
@RequestMapping("\${skeleton.app-control.admin.base-path:/api/v1/admin/app-control}")
class AppControlAdminController(
    private val control: AppControl,
    private val access: OperatorAccess,
) {
    @GetMapping
    fun state(authentication: Authentication?): DataResponse<AppControlStateResponse> {
        operator(authentication, AppControlPermissions.READ)
        return Response.ok(control.snapshot().toStateResponse())
    }

    @PutMapping("/flags/{key}")
    fun setFlag(authentication: Authentication?, @PathVariable key: String, @RequestBody body: FlagBody): DataResponse<FlagResponse> {
        val operator = operator(authentication, AppControlPermissions.WRITE)
        val enabled = body.enabled ?: throw FieldValidationException("enabled", "Required", "enabled is required")
        val s = control.setFlag(operator.accountId, key, enabled, body.clientVisible ?: false, body.description)
        return Response.ok(FlagResponse(s.key, s.enabled, s.clientVisible, s.description, s.updatedBy, s.updatedAt))
    }

    @DeleteMapping("/flags/{key}")
    fun deleteFlag(authentication: Authentication?, @PathVariable key: String): ResponseEntity<Void> {
        control.deleteFlag(operator(authentication, AppControlPermissions.WRITE).accountId, key)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/maintenance")
    fun setMaintenance(authentication: Authentication?, @RequestBody body: MaintenanceBody): DataResponse<MaintenanceResponse> {
        val operator = operator(authentication, AppControlPermissions.WRITE)
        val enabled = body.enabled ?: throw FieldValidationException("enabled", "Required", "enabled is required")
        val s = control.setMaintenance(operator.accountId, enabled, body.message, body.endsAt)
        return Response.ok(MaintenanceResponse(s.enabled, s.message, s.endsAt, s.updatedBy, s.updatedAt))
    }

    @PutMapping("/platforms/{platform}")
    fun setPlatform(authentication: Authentication?, @PathVariable platform: String, @RequestBody body: PlatformBody): DataResponse<PlatformResponse> {
        val operator = operator(authentication, AppControlPermissions.WRITE)
        if (body.minVersion == null && body.recommendedVersion == null && body.storeUrl == null) {
            throw FieldValidationException("minVersion", "Required", "Give at least one of minVersion, recommendedVersion, storeUrl (DELETE removes the platform)")
        }
        val s = control.setPlatform(operator.accountId, platform, body.minVersion, body.recommendedVersion, body.storeUrl)!!
        return Response.ok(PlatformResponse(s.platform, s.minVersion, s.recommendedVersion, s.storeUrl, s.updatedBy, s.updatedAt))
    }

    @DeleteMapping("/platforms/{platform}")
    fun deletePlatform(authentication: Authentication?, @PathVariable platform: String): ResponseEntity<Void> {
        control.setPlatform(operator(authentication, AppControlPermissions.WRITE).accountId, platform, null, null, null)
        return ResponseEntity.noContent().build()
    }

    /** 익명 401 · [permission] 없음 403 */
    private fun operator(authentication: Authentication?, permission: String): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, permission) }
    }
}
