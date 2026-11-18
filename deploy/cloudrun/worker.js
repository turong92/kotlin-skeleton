// API 프록시 Worker — `<도메인><api_prefix>/*` 요청을 Cloud Run 원점으로 넘긴다 (terraform 이 스크립트와 바인딩을 올린다).
//
// 무료 플랜의 Cloudflare 프록시는 Host 를 덮어쓸 수 없어 도메인 → `*.run.app` CNAME 이 안 된다. 이 Worker 가 대신 받아 원점으로 넘긴다.
//
// 하는 일
//  1. API_PREFIX 아래만 넘긴다(허용 목록) — `/internal/*` 를 포함한 나머지는 404. Cloud Tasks · Scheduler 는 run.app 을 직접 부른다
//  2. 클라이언트가 보낸 `X-Origin-Auth` · `X-Forwarded-For` · `X-Real-IP` 는 버리고 `X-Origin-Auth` 에 비밀을 붙인다 —
//     앱(client-ip.mode=edge-secret)은 이 헤더가 맞는 요청에서만 `CF-Connecting-IP` 를 믿고, 헤더가 없는 직접 호출을 403 한다
//  3. 본문은 스트림 그대로, 응답도 그대로(캐시하지 않는다), 리다이렉트는 따라가지 않는다
//
// 바인딩: ORIGIN_URL(원점 주소) · API_PREFIX(`/api/v1/` — 끝 슬래시 포함) · 비밀 ORIGIN_AUTH_SECRET

const SECRET_HEADER = 'X-Origin-Auth'

export default {
  async fetch(request, env) {
    const url = new URL(request.url)
    // `new URL` 이 `.` · `..` · `%2e%2e` 를 이미 접었다 — 접힌 pathname 으로만 판정한다.
    // 인코딩된 슬래시 · 역슬래시는 원점이 어떻게 풀지 모르므로 접두사 뒤에서도 받지 않는다.
    // 거부 목록(`/internal`)은 표기를 바꿔 비켜 가므로 허용 목록만 쓴다.
    if (
      !env.API_PREFIX ||
      !url.pathname.startsWith(env.API_PREFIX) ||
      /%2f|%5c/i.test(url.pathname)
    ) {
      return new Response('Not Found', { status: 404 })
    }
    if (!env.ORIGIN_URL || !env.ORIGIN_AUTH_SECRET) {
      // 설정이 빠진 채 배포됐다 — 비밀 없이 넘기면 원점이 403 이니 여기서 분명히 실패한다
      return new Response('Bad Gateway', { status: 502 })
    }

    // 호스트는 문자열 합치기로 고정한다. `new URL(path, base)` 는 path 가 `//evil.example/…` 면 호스트를 바꿔 비밀이 샌다
    const origin = new URL(env.ORIGIN_URL)
    const upstream = new URL(origin.origin + url.pathname + url.search)
    if (upstream.origin !== origin.origin) {
      return new Response('Bad Request', { status: 400 })
    }

    // 메서드 · 본문 스트림 · 나머지는 요청 그대로(Request 를 init 으로 넘긴다 — 본문을 읽지 않는다)
    const proxied = new Request(upstream, request)
    const headers = new Headers(proxied.headers)
    headers.delete(SECRET_HEADER)
    headers.delete('X-Forwarded-For')
    headers.delete('X-Real-IP')
    headers.set(SECRET_HEADER, env.ORIGIN_AUTH_SECRET)
    // Host 는 넘기지 않는다 — fetch 가 업스트림 호스트로 채운다(Cloud Run 이 요구)
    headers.delete('Host')
    return fetch(new Request(proxied, { headers, redirect: 'manual' }))
  },
}
