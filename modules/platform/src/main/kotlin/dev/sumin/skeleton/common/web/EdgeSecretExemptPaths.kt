package dev.sumin.skeleton.common.web

/**
 * `skeleton.web.client-ip.mode=edge-secret` 에서 원점 비밀(`X-Origin-Auth`) 없이도 **POST** 로 닿을 수 있는 경로를 더하는 고리 — 정확히 일치하는 경로만.
 * 엣지(Worker)를 거치지 않고 플랫폼이 직접 부르는 주소(예: 작업 큐의 깨우기 주소)가 쓴다. 면제 경로에서는 `CF-Connecting-IP` · `CF-IPCountry` 를 믿지 않는다.
 * 여러 빈이 낸 집합을 모은다. 경로는 요청마다 읽는다.
 */
fun interface EdgeSecretExemptPaths {
    fun paths(): Set<String>
}
