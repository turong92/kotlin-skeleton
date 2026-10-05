package dev.sumin.skeleton.app.api

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.security.core.Authentication

/**
 * 스타터에 컨트롤러를 쓰는 사람이 처음 만나는 것: 호출자(Authentication) · 검증(@Valid) · OpenAPI(@Operation) 가 앱의 **컴파일** 클래스패스에 있어야 한다.
 * 모듈의 `implementation` 의존은 앱 컴파일에 보이지 않는다 — 런타임에만 있어서 Class.forName 으로는 못 잡는다. 이 파일이 컴파일되는 것이 시험이다.
 */
class ControllerAuthoringClasspathTest {
    @Test
    fun `the starter compiles a controller that takes the caller, validates and documents`() {
        assertEquals("Operation", Operation::class.simpleName)
        assertEquals("Valid", Valid::class.simpleName)
        assertEquals("Authentication", Authentication::class.simpleName)
    }
}
