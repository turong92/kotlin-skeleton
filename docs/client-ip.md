# Client IP (`skeleton.web.client-ip`)

Rate limits, the Redis rate limit, and the anonymous idempotency scope all need "who is calling". They no longer read
`request.remoteAddr`; they ask `ClientIps` (platform), which applies one explicit rule.

## Read this first — the default is spoofable

`ForwardedHeaderFilter` is on by default (`skeleton.web.forwarded-headers.enabled`) so redirects and links see the proxy's scheme and host.
The same filter **overwrites `remoteAddr`** from `X-Forwarded-For` / `Forwarded: for=…`. With no client-IP mode set, a caller that
connects directly can send `X-Forwarded-For: <any address>` and pick its own rate-limit key (proved in `ClientIpChainTest`).
Behind a proxy that same behaviour is what currently shows the real client, so **the default did not change**:
unset keeps `remoteAddr` as the servlet layer reports it, and the app logs a WARN at startup. A public service must set a mode.

## Modes

```yaml
skeleton:
  web:
    client-ip:
      mode: proxy                     # direct | proxy | cloudflare   (unset = legacy, see above)
      trusted-proxies: ["127.0.0.0/8", "::1/128"]   # peers whose forwarding headers are believed
```

| mode | use when | client is |
|---|---|---|
| `direct` | nothing sits in front of the app | the socket peer; no header is read |
| `proxy` | a reverse proxy you run (Caddy, nginx) appends to `X-Forwarded-For` | walk `X-Forwarded-For` right to left, skip trusted hops, first untrusted address wins; entries that are not IPs stop the walk (no DNS) |
| `cloudflare` | only Cloudflare reaches the origin (Tunnel with a private origin port, or an origin that verifies the zone's client certificate) | `CF-Connecting-IP` from a trusted peer; missing/garbage falls back to the `proxy` rule |

Headers are only read when the socket peer is inside `trusted-proxies`. A Cloudflare-range address alone is **not** trusted
(Workers `connect()` and WARP reach any origin from Cloudflare addresses). With `cloudflare`/`proxy` and an empty
`trusted-proxies` the app refuses to start; so does an entry that is not an IP/CIDR.

The rate-limit key (`ClientAddress.limitKey`) is the address, and for IPv6 the `/64` prefix — rotating addresses inside one
subnet is one client. `ClientAddress.ip` is the exact address (audit logs, captcha `remoteip`).

## How it works

When a mode is set, `ClientIpFilter` runs **before** `ForwardedHeaderFilter` (order `HIGHEST_PRECEDENCE`), resolves the client once
from the untouched socket peer and headers, and stores it in a request attribute. `ClientIps.of(request)` reads that attribute.
Code that still reads `request.remoteAddr` after the filter chain sees the rewritten value — use `ClientIps`.

The three skeleton call sites go through it: platform `ClientIpRateLimitKeyResolver`, `redis-rate-limit`
`PrincipalAwareRateLimitKeyResolver` (`ip:<limitKey>`), `idempotency` `PrincipalIdempotencyScopeResolver` (`anonymous:<limitKey>`).
Replace the `ClientIps` bean to change the rule for all of them.
