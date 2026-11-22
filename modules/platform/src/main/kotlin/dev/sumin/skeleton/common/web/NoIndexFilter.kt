package dev.sumin.skeleton.common.web

import dev.sumin.skeleton.common.deploy.DeployEnv
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * prod 가 아닌 환경(`skeleton.env=local|stage`)의 모든 응답에 `X-Robots-Tag: noindex, nofollow` — 밖에 연 개발 · 시험 주소가 검색에 잡히지 않게 한다.
 * robots.txt 의 `Disallow` 로 막지 않는다: 막으면 크롤러가 이 헤더를 읽지 못해 이미 잡힌 주소가 빠지지 않는다.
 */
class NoIndexFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        // 체인 앞에서 단다 — 뒤에서 오류가 나도 헤더가 남는다
        response.setHeader(HEADER, VALUE)
        filterChain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Robots-Tag"
        const val VALUE = "noindex, nofollow"

        /** 스위치가 local · stage 일 때만 켠다. 비었으면(중립) · prod 면 응답을 건드리지 않는다 */
        fun enabledFor(env: DeployEnv?): Boolean = env == DeployEnv.LOCAL || env == DeployEnv.STAGE
    }
}
