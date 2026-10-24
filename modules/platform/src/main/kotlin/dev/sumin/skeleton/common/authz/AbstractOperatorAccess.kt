package dev.sumin.skeleton.common.authz

import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

/**
 * 한 요청 안에서 같은 `accountId` 의 [permissionsOf] 를 한 번만 계산한다(요청 속성에 메모). 요청 밖 호출은 메모 없이 매번 계산한다.
 * 요청 사이 캐시는 없다 — 회수가 다음 요청부터 먹어야 한다.
 */
abstract class AbstractOperatorAccess : OperatorAccess {
    protected abstract fun compute(ref: OperatorRef): Set<String>

    /** [rolesOf] 의 계산 — 기본은 토큰의 역할. 저장소를 읽는 구현이 덮는다 */
    protected open fun computeRoles(ref: OperatorRef): Set<String> = ref.tokenRoles

    final override fun permissionsOf(ref: OperatorRef): Set<String> = memoized("permissions", ref) { compute(ref) }

    final override fun rolesOf(ref: OperatorRef): Set<String> = memoized("roles", ref) { computeRoles(ref) }

    private fun memoized(kind: String, ref: OperatorRef, calculate: () -> Set<String>): Set<String> {
        val attributes = RequestContextHolder.getRequestAttributes() ?: return calculate()
        // 구현 · 토큰 역할까지 키에 넣는다 - 감싸는 구현 둘이나 같은 계정의 다른 토큰이 한 메모를 나눠 쓰지 않게
        val name = "${OperatorAccess::class.java.name}.$kind.${javaClass.name}.${ref.accountId}.${ref.tokenRoles.sorted()}"
        // 끝난 요청의 속성(상속된 자식 스레드)은 읽기 · 쓰기에서 IllegalStateException — 메모 없이 계산한다. calculate 는 이 try 밖이라 두 번 불리지 않는다
        val memo = try {
            @Suppress("UNCHECKED_CAST")
            attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST) as Set<String>?
        } catch (_: IllegalStateException) {
            return calculate()
        }
        if (memo != null) return memo
        val computed = calculate()
        try {
            attributes.setAttribute(name, computed, RequestAttributes.SCOPE_REQUEST)
        } catch (_: IllegalStateException) {
            // 계산하는 사이 요청이 끝났다 - 메모만 건너뛴다
        }
        return computed
    }
}
