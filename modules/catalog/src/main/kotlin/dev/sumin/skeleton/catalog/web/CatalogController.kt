package dev.sumin.skeleton.catalog.web

import dev.sumin.skeleton.catalog.CatalogReader
import dev.sumin.skeleton.catalog.ProductKind
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.payment.web.PaymentCountryRouting
import jakarta.servlet.http.HttpServletRequest
import java.util.concurrent.TimeUnit
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 없이 보이는 상품 · 가격(`skeleton.catalog.http.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 가격은 **이 요청의 통화**(결제의 나라 라우팅이 정한다 - 브라우저가 고르지 않는다)로 거른다.
 * 통화가 나라마다 달라지므로 응답은 개인 캐시 60초이고 나라를 정하는 헤더로 갈린다(`Vary`).
 */
@RestController
@RequestMapping("\${skeleton.catalog.http.base-path:/api/v1/catalog}")
class CatalogController(
    private val reader: CatalogReader,
    /** 결제의 나라 라우팅 - 없으면(결제 HTTP 가 없는 조립) 통화를 모르는 요청으로 읽는다 */
    private val routing: () -> PaymentCountryRouting?,
) {
    @GetMapping("/products")
    fun products(@RequestParam(required = false) kind: String?, request: HttpServletRequest): ResponseEntity<DataResponse<PublicCatalogResponse>> {
        val wanted = kind?.takeIf { it.isNotBlank() }?.let { raw ->
            ProductKind.entries.firstOrNull { it.name == raw.trim().uppercase() }
                ?: throw ApplicationException("kind is SUBSCRIPTION or ONE_TIME", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        }
        val routing = routing()
        val route = routing?.of(request)
        val catalog = reader.read(wanted, route?.let { routing.currencyOf(it) })
        val vary = if (route?.devSwitch == true) arrayOf("CF-IPCountry", "X-Dev-Country") else arrayOf("CF-IPCountry")
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(CACHE_SECONDS, TimeUnit.SECONDS).cachePrivate()).varyBy(*vary).body(Response.ok(catalog.toResponse()))
    }

    private companion object {
        const val CACHE_SECONDS = 60L
    }
}
