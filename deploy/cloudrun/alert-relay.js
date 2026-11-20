// 경보 중계 Worker — Cloud Monitoring 의 webhook_basicauth 알림을 Discord 웹훅 한 줄로 바꿔 보낸다 (terraform 이 스크립트와 바인딩을 올린다).
//
// Cloud Monitoring 웹훅 본문(`{ incident: { policy_name, state, url, … } }`)은 Discord 형식이 아니다.
// 채널은 webhook_basicauth 라 `Authorization: Basic base64(relay:<토큰>)` 헤더를 싣는다.
// 헤더가 틀리면 401, POST 가 아니면 405, `incident` 가 없으면 400, Discord 가 거절하거나 못 닿으면 502.
//
// 바인딩(둘 다 secret_text): RELAY_TOKEN · DISCORD_WEBHOOK_URL

const USERNAME = 'relay' // module/monitoring.tf 의 채널 username 과 같다

export default {
  async fetch(request, env) {
    if (!env.RELAY_TOKEN || !env.DISCORD_WEBHOOK_URL) {
      return new Response('Bad Gateway', { status: 502 })
    }
    const enc = new TextEncoder()
    const given = enc.encode(request.headers.get('Authorization') ?? '')
    const expected = enc.encode('Basic ' + btoa(`${USERNAME}:${env.RELAY_TOKEN}`))
    // 길이가 다르면 비교하지 않고 거절한다(timingSafeEqual 은 같은 길이만 받는다)
    if (given.byteLength !== expected.byteLength || !crypto.subtle.timingSafeEqual(given, expected)) {
      return new Response('Unauthorized', { status: 401 })
    }
    if (request.method !== 'POST') {
      return new Response('Method Not Allowed', { status: 405 })
    }

    let incident
    try {
      incident = (await request.json()).incident
    } catch {
      incident = undefined
    }
    if (!incident || typeof incident !== 'object') {
      return new Response('Bad Request', { status: 400 })
    }

    const state = incident.state === 'closed' ? '닫힘' : '열림'
    const content = `[경보 ${state}] ${incident.policy_name ?? '이름 없는 정책'} - ${incident.url ?? ''}`.trimEnd()
    try {
      const sent = await fetch(env.DISCORD_WEBHOOK_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content }),
      })
      if (sent.ok) return new Response('ok')
    } catch {
      // 네트워크 오류도 502 — 아래로 떨어진다
    }
    return new Response('Bad Gateway', { status: 502 })
  },
}
