// 경보 중계 Worker — Cloud Monitoring 의 webhook_tokenauth 알림을 Discord 웹훅 한 줄로 바꿔 보낸다 (terraform 이 스크립트와 바인딩을 올린다).
//
// Cloud Monitoring 웹훅 본문(`{ incident: { policy_name, state, url, … } }`)은 Discord 형식이 아니다. 채널은 주소 뒤에 `?auth_token=<토큰>` 을 붙여 부른다.
// 토큰이 틀리면 401, POST 가 아니면 405, `incident` 가 없으면 400, Discord 가 거절하면 502.
//
// 바인딩(둘 다 secret_text): RELAY_TOKEN · DISCORD_WEBHOOK_URL

export default {
  async fetch(request, env) {
    if (!env.RELAY_TOKEN || !env.DISCORD_WEBHOOK_URL) {
      return new Response('Bad Gateway', { status: 502 })
    }
    const token = new URL(request.url).searchParams.get('auth_token')
    if (token !== env.RELAY_TOKEN) {
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
    const sent = await fetch(env.DISCORD_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    })
    return sent.ok ? new Response('ok') : new Response('Bad Gateway', { status: 502 })
  },
}
