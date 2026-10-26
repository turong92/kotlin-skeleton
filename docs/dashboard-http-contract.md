# Admin dashboard — HTTP contract (kotlin-skeleton backend)

Audience: the frontend (react-skeleton `@skeleton/dashboard`). Backend module: `dashboard` (see [modules/dashboard.md](modules/dashboard.md)).
Conventions (envelope, error body, tokens): [account-http-contract.md](account-http-contract.md) section 0 — unchanged here.

## 0. Conventions

- Base path `/api/v1/admin/dashboard`. **Off by default**: the routes exist only when the app has `dashboard` and sets `skeleton.dashboard.admin.enabled=true`; otherwise every route below is `404`.
- Envelope and errors are the platform ones: `{ value | values, meta }`, errors `{ code, title, status, detail?, traceId, spanId, timestamp, errors?, data? }`. Branch on `code`.
- Needs the permission `dashboard.read` on every route: no token is `401`, a signed-in caller without it is `403 COMMON.FORBIDDEN` (checked before anything else, also for unknown ranking keys). The caller's permissions come from `GET /api/v1/auth/me` (`principal.permissions`, `["*"]` = full access).
- `from` / `to` are dates (`yyyy-MM-dd`) in the dashboard time zone (`range.zone`), both ends included; "today" is `from=to=<today>`. Both missing = today. Only one of them, a non-date, `from` after `to`, or more than 366 days → `400 DASHBOARD.RANGE_INVALID`.
- `interval` (the chart step): one day → `HOUR`, up to 92 days → `DAY`, more → `WEEK`. `previous` is the period right before, of the same length.
- No request calls an outside service: numbers come from short server caches (per project), so a screen may poll every 60 seconds.

## 1. Overview

### GET /api/v1/admin/dashboard?from=2026-10-20&to=2026-10-26&compare=true

```json
{ "value": {
  "range":    { "from": "2026-10-20", "to": "2026-10-26", "zone": "Asia/Seoul", "interval": "DAY" },
  "previous": { "from": "2026-10-13", "to": "2026-10-19" },
  "metrics": [ {
      "key": "payment.net-revenue", "label": "Net revenue", "unit": "MONEY", "source": "db",
      "status": "OK", "freshness": { "kind": "LIVE", "asOf": "2026-10-26T05:00:00Z" },
      "value": [ { "amount": 120000, "currency": "KRW" }, { "amount": 990, "currency": "USD" } ],
      "previousValue": [ { "amount": 100000, "currency": "KRW" } ],
      "series": [ { "at": "2026-10-19T15:00:00Z", "value": 12000 } ],
      "previousSeries": [ { "at": "2026-10-12T15:00:00Z", "value": 9000 } ],
      "link": "/admin/payments" } ],
  "rankings": [ { "key": "cloudflare.referrers", "group": "traffic", "label": "Sources", "source": "cloudflare",
      "status": "NOT_CONNECTED", "freshness": null, "columns": ["Source", "Visits"], "rows": [ { "label": "google.com", "values": [120] } ] } ],
  "funnel": { "steps": [ { "key": "account.signed-up", "label": "Signed up", "reached": 128, "rate": 1.0 } ],
              "reference": { "key": "cloudflare.visits", "label": "Visits", "value": 3200, "rate": 0.04 } } } }
```

- `previous`, `previousValue`, `previousSeries` are `null` unless `compare=true`. Even with `compare=true` a metric that cannot be compared (`auth-session.active` — its past values are overwritten) has `previousValue` and `previousSeries` `null`: show "not comparable" instead of a delta.
- `unit`: `PEOPLE` · `COUNT` · `MONEY` · `PERCENT`. Not money → `value` has one element with `currency: null`. Money → one element per currency, **never summed**; the configured currency (`skeleton.dashboard.currency`) first, the rest by amount. `series` is the first currency only.
- `PERCENT` values are **integers in basis points** (1% = 100, 38.2% = 3820); the screen divides by 100. The funnel `rate`s are plain fractions (0.5 = 50%), not basis points.
- `status`: `OK` · `NOT_CONNECTED` (no key configured) · `NO_HISTORY` (a past period without `dashboard-jdbc`) · `ERROR` (the source failed — the response is still `200`). Anything but `OK` has an empty `value` / `series` / `rows` and, mostly, `freshness: null`.
- `freshness.kind`: `LIVE` (`asOf` = when the server computed it) · `CACHED` (`asOf` = when it was fetched from the outside) · `DAILY` (`asOf` = when the daily load ran).
- `rankings` hold the top 10 rows; `group` is `traffic` · `pages` · `geo` · `app` (a card per group, a tab per ranking). `funnel` is `null` when the app has no cohort source; its first step is the cohort itself (`rate` 1.0), the other `rate`s are against the cohort size. `reference` (anonymous visits, not part of the cohort) is `null` when the app has no such metric or it is not `OK`; its `rate` is cohort size / visits.
- `ga.returning` is a **ranking**, not a tile: group `traffic`, columns `["Users"]`, exactly two rows `New` and `Returning` with the user counts (`values: [n]`). The screen computes the share from the two counts. (Five GA rankings in all, as in the key list below.)
- Tile keys: `cloudflare.visits` · `cloudflare.pageviews` · `account.signed-up` · `auth-session.active` · `payment.net-revenue` · `payment.paid-count`. Ranking keys: `cloudflare.{referrers,paths,countries,devices}` · `ga.{channels,campaigns,landing-pages,page-transitions,returning}` · `app.*`.

## 2. One ranking

### GET /api/v1/admin/dashboard/rankings/{key}?from&to&limit=100
`limit` is clamped to 1..500. Response `{ "value": <ranking as above> }`. Unknown key → `404 DASHBOARD.RANKING_NOT_FOUND`.

## 3. Errors

| `code` | status | when |
|---|---|---|
| `DASHBOARD.RANGE_INVALID` | 400 | the date range cannot be used |
| `DASHBOARD.RANKING_NOT_FOUND` | 404 | no ranking with that key |
| `COMMON.FORBIDDEN` | 403 | no `dashboard.read` |
