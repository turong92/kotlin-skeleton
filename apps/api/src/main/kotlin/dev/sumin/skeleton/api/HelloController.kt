package dev.sumin.skeleton.api

import dev.sumin.skeleton.common.ApiResponse
import dev.sumin.skeleton.common.ApiValueResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class HelloResponse(
    val message: String,
    val timestamp: String,
)

/**
 * 샘플 컨트롤러. 실제 앱에선 삭제하거나 대체.
 *
 * - REST 네임스페이스는 `/api/v1/` 하위
 * - 성공 응답은 ApiResponse envelope 로 감싼다
 * - 에러는 [dev.sumin.skeleton.common.ApplicationException] 상속해서 throw하면 [dev.sumin.skeleton.common.GlobalExceptionHandler]가 표준 응답으로 변환
 */
@RestController
@RequestMapping("/api/v1")
class HelloController {
    @GetMapping("/hello")
    fun hello(): ApiValueResponse<HelloResponse> =
        ApiResponse.value(
            HelloResponse(
                message = "Hello from Kotlin backend!",
                timestamp = Instant.now().toString(),
            ),
        )
}
