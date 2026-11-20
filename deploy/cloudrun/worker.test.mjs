// node --test deploy/cloudrun/worker.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import worker from './worker.js'

const ENV = {
  ORIGIN_URL: 'https://acme-api-123456789012.asia-northeast3.run.app',
  API_PREFIX: '/api/v1/',
  ORIGIN_AUTH_SECRET: 'origin-secret-for-test',
}

// fetch 를 가로채 호출을 모은다. 원점으로 나간 요청만 본다.
async function run(url, init = {}, env = ENV) {
  const calls = []
  const realFetch = globalThis.fetch
  globalThis.fetch = async (req) => {
    calls.push(req)
    return new Response('ok', { status: 200 })
  }
  try {
    const res = await worker.fetch(new Request(url, init), env)
    return { res, calls }
  } finally {
    globalThis.fetch = realFetch
  }
}

test('worker never forwards outside the api prefix', async () => {
  const paths = [
    '/api/v1/../internal/jobs/drain',
    '/api/v1%2F..%2Finternal',
    '/api/v1',
    '/api/v1x',
    '/api/v1/..%2Finternal/jobs/drain',
    '/health',
  ]
  for (const p of paths) {
    const { res, calls } = await run('https://example.com' + p)
    assert.equal(res.status, 404, p)
    assert.equal(calls.length, 0, p)
  }
})

test('worker sets origin secret and strips client copies', async () => {
  const { res, calls } = await run('https://example.com/api/v1/ping', {
    headers: {
      'X-Origin-Auth': 'forged',
      'X-Forwarded-For': '6.6.6.6',
      'X-Real-IP': '6.6.6.6',
      'CF-Connecting-IP': '1.2.3.4',
    },
  })
  assert.equal(res.status, 200)
  assert.equal(calls.length, 1)
  const h = calls[0].headers
  assert.equal(h.get('X-Origin-Auth'), ENV.ORIGIN_AUTH_SECRET)
  assert.equal(h.get('X-Forwarded-For'), null)
  assert.equal(h.get('X-Real-IP'), null)
  assert.equal(h.get('CF-Connecting-IP'), '1.2.3.4')
  assert.equal(new URL(calls[0].url).origin, ENV.ORIGIN_URL)
  assert.equal(calls[0].redirect, 'manual')
})

test('worker keeps query string and method', async () => {
  const { calls } = await run('https://example.com/api/v1/items?a=1&b=x%20y', {
    method: 'POST',
    body: 'payload',
  })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'POST')
  const u = new URL(calls[0].url)
  assert.equal(u.pathname, '/api/v1/items')
  assert.equal(u.search, '?a=1&b=x%20y')
  assert.equal(await calls[0].text(), 'payload')
})

test('worker rewrites only GET/HEAD <prefix>health to origin /health', async () => {
  for (const method of ['GET', 'HEAD']) {
    const { res, calls } = await run('https://example.com/api/v1/health?x=1', { method })
    assert.equal(res.status, 200, method)
    assert.equal(calls.length, 1, method)
    const u = new URL(calls[0].url)
    assert.equal(u.origin, ENV.ORIGIN_URL, method)
    assert.equal(u.pathname, '/health', method)
    assert.equal(u.search, '', method)
    assert.equal(calls[0].headers.get('X-Origin-Auth'), ENV.ORIGIN_AUTH_SECRET, method)
  }
})

test('worker does not rewrite lookalikes or other methods of the health path', async () => {
  const cases = [
    ['GET', '/api/v1/health/x', '/api/v1/health/x'],
    ['GET', '/api/v1/healthz', '/api/v1/healthz'],
    ['POST', '/api/v1/health', '/api/v1/health'],
  ]
  for (const [method, path, expected] of cases) {
    const { calls } = await run('https://example.com' + path, { method })
    assert.equal(calls.length, 1, path)
    assert.equal(new URL(calls[0].url).pathname, expected, `${method} ${path}`)
  }
  const { res, calls } = await run('https://example.com/health')
  assert.equal(res.status, 404)
  assert.equal(calls.length, 0)
})
