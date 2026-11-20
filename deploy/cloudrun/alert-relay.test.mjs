// node --test deploy/cloudrun/alert-relay.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import relay from './alert-relay.js'

const ENV = {
  RELAY_TOKEN: 'relay-token-for-test',
  DISCORD_WEBHOOK_URL: 'https://discord.example.com/api/webhooks/1/abc',
}

const INCIDENT = {
  version: '1.2',
  incident: {
    policy_name: 'acme 5xx',
    state: 'open',
    url: 'https://console.cloud.google.com/monitoring/alerting/incidents/0.abc?project=acme-proj',
  },
}

// Discord 로 나가는 fetch 만 모은다
async function run(token, body, { method = 'POST', discordStatus = 204 } = {}) {
  const calls = []
  const realFetch = globalThis.fetch
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init })
    return new Response(null, { status: discordStatus })
  }
  try {
    const q = token === null ? '' : `?auth_token=${token}`
    const res = await relay.fetch(
      new Request('https://example.com/_alert-relay' + q, {
        method,
        body: method === 'POST' ? JSON.stringify(body) : undefined,
      }),
      ENV,
    )
    return { res, calls }
  } finally {
    globalThis.fetch = realFetch
  }
}

test('relay rejects a missing or wrong token with 401 and calls nothing', async () => {
  for (const t of [null, 'wrong', '']) {
    const { res, calls } = await run(t, INCIDENT)
    assert.equal(res.status, 401, String(t))
    assert.equal(calls.length, 0, String(t))
  }
})

test('relay turns an opened incident into one short Korean Discord line', async () => {
  const { res, calls } = await run(ENV.RELAY_TOKEN, INCIDENT)
  assert.equal(res.status, 200)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, ENV.DISCORD_WEBHOOK_URL)
  assert.equal(calls[0].init.method, 'POST')
  const body = JSON.parse(calls[0].init.body)
  assert.equal(body.content, `[경보 열림] acme 5xx - ${INCIDENT.incident.url}`)
})

test('relay says closed for a closed incident', async () => {
  const closed = { incident: { ...INCIDENT.incident, state: 'closed' } }
  const { calls } = await run(ENV.RELAY_TOKEN, closed)
  assert.equal(JSON.parse(calls[0].init.body).content, `[경보 닫힘] acme 5xx - ${INCIDENT.incident.url}`)
})

test('relay answers 502 when Discord refuses, 400 for a body without incident, 405 for non-POST', async () => {
  assert.equal((await run(ENV.RELAY_TOKEN, INCIDENT, { discordStatus: 500 })).res.status, 502)
  assert.equal((await run(ENV.RELAY_TOKEN, {})).res.status, 400)
  assert.equal((await run(ENV.RELAY_TOKEN, null, { method: 'GET' })).res.status, 405)
})
