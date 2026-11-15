package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.TestAccountService
import dev.sumin.skeleton.account.authz.AccountPermissions
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 시험 계정 표시 — 운영 중 점검 · 시험 결제용 계정을 통계 · 매출 · 공지 대상 `all` 에서 뺀다. `skeleton.account.admin.enabled=true` 일 때만([AccountWebAutoConfiguration]).
 * 읽기는 `account.read`, 켜고 끄기는 `account.manage` — 매출을 줄이는 표시라서 읽기 권한만으로는 바꿀 수 없다. 바뀔 때마다 감사 기록 한 줄.
 */
@RestController
@RequestMapping("/api/v1/admin/accounts/{id}/test-account")
@Tag(name = "Account admin")
class AdminTestAccountController(private val callers: AccountCallers, private val tests: TestAccountService) {
    @Operation(summary = "Whether the account is marked as a test account", description = "404 for an unknown account")
    @GetMapping
    fun get(authentication: Authentication?, @PathVariable id: String): DataResponse<TestAccountMarkResponse> {
        callers.requirePermission(authentication, AccountPermissions.READ)
        val mark = tests.find(id)
        return Response.ok(TestAccountMarkResponse(mark != null, mark?.markedBy, mark?.markedAt))
    }

    @Operation(
        summary = "Mark as a test account (left out of statistics, revenue, payment totals and the `all` notice audience; the account itself stays as it is)",
        description = "Idempotent. 404 for an unknown account, 410 ACCOUNT.ERASED for an erased one. Writes one audit line when the mark changes.",
    )
    @PutMapping
    fun mark(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val caller = callers.requirePermission(authentication, AccountPermissions.MANAGE)
        tests.mark(caller.accountId, id)
        return Response.noContent()
    }

    @Operation(summary = "Clear the test-account mark (the account counts in statistics again, also for what it did while marked)", description = "Idempotent. 404 for an unknown account.")
    @DeleteMapping
    fun clear(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val caller = callers.requirePermission(authentication, AccountPermissions.MANAGE)
        tests.clear(caller.accountId, id)
        return Response.noContent()
    }
}

data class TestAccountMarkResponse(val marked: Boolean, val markedBy: String?, val markedAt: Instant?)
