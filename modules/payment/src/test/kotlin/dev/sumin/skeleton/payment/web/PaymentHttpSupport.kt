package dev.sumin.skeleton.payment.web

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** 웹 시험이 같이 쓰는 도구 — 로그인한 호출자(보안 체인은 `auth` 가 세운다. 이 모듈 시험에서는 `Authentication` 만 건넨다)와 JSON 읽기. */
internal object PaymentHttpSupport {
    val json = ObjectMapper()

    fun MockHttpServletRequestBuilder.asUser(accountId: String, vararg roles: String): MockHttpServletRequestBuilder =
        principal(UsernamePasswordAuthenticationToken(accountId, "n/a", roles.map { SimpleGrantedAuthority("ROLE_$it") }))

    fun MockHttpServletRequestBuilder.asOperator(accountId: String = "operator_1"): MockHttpServletRequestBuilder = asUser(accountId, "ADMIN")

    fun read(body: String): JsonNode = json.readTree(body)

    /** 응답 JSON 에 나온 모든 객체 키(중첩 포함). */
    fun keys(node: JsonNode): Set<String> = buildSet {
        fun walk(n: JsonNode) {
            if (n.isObject) n.properties().forEach { (name, child) -> add(name); walk(child) }
            if (n.isArray) n.forEach(::walk)
        }
        walk(node)
    }

    /** 응답 JSON 의 모든 글자 값(키는 빼고). */
    fun strings(node: JsonNode): Set<String> = buildSet {
        fun walk(n: JsonNode) {
            if (n.isString) add(n.stringValue())
            if (n.isObject) n.properties().forEach { (_, child) -> walk(child) }
            if (n.isArray) n.forEach(::walk)
        }
        walk(node)
    }
}
