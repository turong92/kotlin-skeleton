package dev.sumin.skeleton.apikey.web

import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.Reauth
import dev.sumin.skeleton.account.web.reauthOf
import dev.sumin.skeleton.apikey.ApiKeys
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import jakarta.validation.Valid
import java.net.URI
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 내 API 키 - 로그인한 사용자의 것. `@ApiKeyScope` 표시가 없으므로 **키로는 닿지 않는다**(키로 키를 만들 수 없다).
 * 이 클래스는 [ApiKeyWebAutoConfiguration] 이 등록한다(`skeleton.api-key.http.enabled=false` 로 끈다).
 */
@RestController
@RequestMapping("\${skeleton.api-key.http.base-path:/api/v1/api-keys}")
class ApiKeyController(
    private val keys: ApiKeys,
    private val reauth: Reauth,
    private val accounts: AccountRepository,
    private val time: TimeProvider,
    private val basePath: String,
) {
    @GetMapping
    fun mine(authentication: Authentication?): ListResponse<ApiKeyResponse> {
        val now = time.now()
        return Response.ok(keys.listOf(caller(authentication).accountId).map { it.toResponse(now) })
    }

    @GetMapping("/scopes")
    fun scopes(authentication: Authentication?): ListResponse<ApiKeyScopeResponse> {
        caller(authentication)
        return Response.ok(keys.declaredScopes().map { it.toResponse() })
    }

    /** 다시 인증 → 발급 → 증거 소비. 토큰은 이 응답에만(`no-store`) */
    @PostMapping
    fun create(authentication: Authentication?, @Valid @RequestBody request: CreateApiKeyRequest): ResponseEntity<DataResponse<CreatedApiKeyResponse>> {
        val caller = caller(authentication)
        val account = accounts.findById(caller.accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val proof = reauth.check(account, reauthOf(request.currentPassword, request.confirmationCode, request.socialReauth), sessionId(authentication))
        val created = keys.create(caller, request.name!!, request.scopes!!, request.expiresAt)
        proof.commit()
        val response = Response.created(URI.create("$basePath/${created.key.id}"), created.toResponse(time.now()))
        return ResponseEntity.status(response.statusCode).headers(response.headers).cacheControl(CacheControl.noStore()).body(response.body)
    }

    @DeleteMapping("/{id}")
    fun revoke(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        keys.revoke(caller(authentication), id)
        return Response.noContent()
    }

    private fun caller(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return OperatorRef(authentication.name, authentication.authorities.map { (it.authority ?: "").removePrefix("ROLE_") }.toSet())
    }

    private fun sessionId(authentication: Authentication?): String? = (authentication?.principal as? CurrentPrincipal)?.sessionId
}
