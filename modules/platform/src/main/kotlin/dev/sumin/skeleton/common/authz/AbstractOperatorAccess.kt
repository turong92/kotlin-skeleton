package dev.sumin.skeleton.common.authz

import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

/**
 * 한 요청 안에서 같은 `accountId` 의 [permissionsOf] 를 한 번만 계산한다(요청 속성에 메모). 요청 밖 호출은 메모 없이 매번 계산한다.
 * 요청 사이 캐시는 없다 — 회수가 다음 요청부터 먹어야 한다.
 */
abstract class AbstractOperatorAccess : OperatorAccess {
    protected abstract fun compute(ref: OperatorRef): Set<String>

    final override fun permissionsOf(ref: OperatorRef): Set<String> {
        val attributes = RequestContextHolder.getRequestAttributes() ?: return compute(ref)
        val name = "${OperatorAccess::class.java.name}.${ref.accountId}"
        @Suppress("UNCHECKED_CAST")
        (attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST) as Set<String>?)?.let { return it }
        return compute(ref).also { attributes.setAttribute(name, it, RequestAttributes.SCOPE_REQUEST) }
    }
}
