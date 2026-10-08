package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.ContactEmailService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.AcceptedOperation
import dev.sumin.skeleton.common.web.ClientIps
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 연락받을 이메일 (`skeleton.account.contact-email.enabled=true` 일 때만 등록된다) — 계정 이메일과 별개, 로그인 · 복구 수단이 아니다. HTTP 변환만. */
@RestController
@RequestMapping("/api/v1/account/contact-email")
@Tag(name = "Account")
class ContactEmailController(
    private val callers: AccountCallers,
    private val contact: ContactEmailService,
    private val clientIps: ClientIps = ClientIps(),
) {
    @Operation(
        summary = "Ask to set (or change) the contact email; a 6-digit code goes to THAT address and nothing is stored until it is entered in this session (202)",
        description = "No re-authentication: this address cannot sign in or recover the account. Any address is accepted, also one that is another account's email (no uniqueness, nothing to reveal).",
    )
    @PostMapping
    @AcceptedOperation
    fun request(authentication: Authentication?, @Valid @RequestBody request: ContactEmailRequest): ResponseEntity<DataResponse<CodeIssuedResponse>> {
        val caller = callers.require(authentication)
        return Response.accepted(CodeIssuedResponse.of("VERIFICATION_SENT", contact.request(caller.accountId, request.email!!, caller.sessionId)))
    }

    @Operation(
        summary = "Enter the code mailed to the contact address (only in the session that asked); the account email gets a notice",
        description = "400 ACCOUNT.CODE_INVALID (data.attemptsLeft), 410 ACCOUNT.CODE_EXPIRED, 429 ACCOUNT.RATE_LIMITED.",
    )
    @PostMapping("/confirm")
    fun confirm(authentication: Authentication?, @Valid @RequestBody request: CodeRequest, http: HttpServletRequest): ResponseEntity<Void> {
        val caller = callers.require(authentication)
        val client = clientIps.of(http)
        contact.confirm(caller.accountId, caller.sessionId, request.code!!, client.ip, client.limitKey)
        return Response.noContent()
    }

    @Operation(summary = "Remove the contact email at once (service messages go to the account email again); 204 also when there was none")
    @DeleteMapping
    fun remove(authentication: Authentication?): ResponseEntity<Void> {
        contact.remove(callers.require(authentication).accountId)
        return Response.noContent()
    }
}
