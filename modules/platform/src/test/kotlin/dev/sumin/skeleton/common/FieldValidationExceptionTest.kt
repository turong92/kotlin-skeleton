package dev.sumin.skeleton.common

import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

/** 서비스 규칙이 던진 필드 오류도 Bean Validation 과 같은 모양(`errors[]`)으로 나간다 */
class FieldValidationExceptionTest {
    @RestController
    class Probe {
        @GetMapping("/field-error")
        fun fieldError(): Map<String, Boolean> = throw FieldValidationException("displayName", "Required", "Display name is required")
    }

    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(Probe()).setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `a field validation exception is a 400 with errors for that field`() {
        mvc.perform(get("/field-error"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[0].field").value("displayName"))
            .andExpect(jsonPath("$.errors[0].code").value("Required"))
            .andExpect(jsonPath("$.errors[0].message").value("Display name is required"))
    }
}
