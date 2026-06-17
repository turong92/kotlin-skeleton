# Notification Workbench Design

## Goal

Make the skeleton realtime notification flow easy to verify from a browser.

The backend modules already exist: `notification`, `notification-sse`,
`notification-websocket`, `auth`, `platform`, and `apps/api` composition
endpoints. The React skeleton also already has API, auth, SSE, STOMP, and
workbench helpers. This slice should connect and harden those pieces into one
repeatable smoke flow instead of adding a product-specific chat or support
feature.

The finished workbench should let a developer or agent confirm this path:

1. Start `apps/api`.
2. Start `react-skeleton`.
3. Log in or use dev-login for REST calls.
4. Open an SSE stream for `demo`.
5. Open a STOMP WebSocket connection for `demo`.
6. Publish a notification through `POST /api/v1/skeleton/notifications`.
7. See the notification arrive through the active realtime channel.
8. Inspect request, response, status, trace id, and event payloads in the
   workbench.

## Repositories

This design crosses two sibling repositories.

- Backend: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton`
- Frontend: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton`

Design and backend roadmap documentation live in the backend repository because
it is the source of the skeleton module contracts. Implementation can commit to
both repositories when needed.

## Scope

### Backend

Use `apps/api` as the composition workbench.

Backend responsibilities:

- Keep the stable publish endpoint:
  `POST /api/v1/skeleton/notifications`.
- Keep SSE delivery through:
  `GET /api/v1/notifications/sse?topic=demo`.
- Keep WebSocket delivery through:
  `/ws/notifications` with STOMP subscriptions to
  `/topic/notifications/demo` and `/user/queue/notifications`.
- Ensure response envelopes for REST smoke endpoints are documented by OpenAPI.
- Ensure trace response headers and request logging still appear for REST and
  SSE handshakes.
- Ensure module catalog output clearly reports whether notification, SSE, and
  WebSocket capabilities are active.

The backend should only gain small smoke endpoints or DTO refinements if current
endpoints cannot express the workbench contract. It should not add domain
controllers such as chat rooms, support tickets, payment notices, or account
notification inboxes.

### Frontend

Use `react-skeleton` as a workbench, not as a product UI.

Frontend responsibilities:

- Keep a single flow trace id across the workbench actions.
- Reuse the standard API client for REST calls.
- Use `fetch` stream parsing for SSE instead of native `EventSource`, because
  the workbench must send auth and trace headers.
- Use a small STOMP frame helper over the browser `WebSocket`.
- Show compact exchange logs for request headers, response envelopes, trace ids,
  statuses, and event payloads.
- Keep notification controls in the existing module workbench surface.

## Authentication Contract

REST and SSE can use either:

- bearer token from `POST /api/v1/auth/login`, or
- dev-login headers such as `X-Dev-Email`, `X-Dev-Username`, or
  `X-Dev-Account-Id`, when the backend profile allows them.

WebSocket should use bearer token as the standard path when WebSocket
authentication is enabled. The current `notification-websocket` module verifies
only a bearer token or configured token header on the STOMP `CONNECT` frame; it
does not read dev-login headers. The workbench should therefore make the token
login path the primary WebSocket smoke flow. If an application wants dev-login
over WebSocket later, that should be a deliberate app-level token verifier or
module enhancement, not an implicit browser convention.

## Realtime Flow

### SSE

1. Frontend opens:
   `GET /api/v1/notifications/sse?topic=demo`.
2. Request includes:
   `Accept: text/event-stream`, `traceparent`, `X-Trace-Id`, and auth headers.
3. Backend sends an initial `connected` event.
4. Frontend stores recent SSE events.
5. A notification published to topic `demo` appears in the SSE event list.

### WebSocket

1. Frontend opens a WebSocket to `/ws/notifications`.
2. Frontend sends STOMP `CONNECT` with bearer token, trace headers, and STOMP
   heartbeat headers.
3. After `CONNECTED`, frontend subscribes to:
   `/topic/notifications/demo` and `/user/queue/notifications`.
4. A notification published to topic `demo` appears on the topic subscription.
5. A notification with `payload.userId` matching the connected principal can
   also appear on the user subscription.

## Error Handling

- REST smoke calls should use the common API error envelope and toast handling.
- SSE connection failures should set the stream status to `error` and keep the
  failed handshake in the exchange log.
- WebSocket `ERROR` frames should be visible in the exchange log without
  crashing the page.
- Missing WebSocket token should be handled as a workbench state problem, not as
  a hidden silent failure.
- Backend publish failures should remain normal API errors; notification module
  failures must not be swallowed in the workbench endpoint.

## OpenAPI And Documentation

The backend documentation should make the workbench contract discoverable from
Swagger and docs:

- `POST /api/v1/skeleton/notifications` response envelope shape.
- SSE endpoint path and topic query behavior.
- WebSocket endpoint, STOMP destinations, and token requirement.
- Example request and expected event shape.

`docs/notification-websocket.md` and any frontend README notes should be updated
only with concrete run steps and expected results, not broad product guidance.

## Testing

Backend verification:

- `apps/api` tests prove notification publish endpoint response envelope and
  module catalog status.
- Existing module tests continue to cover broker, SSE, and WebSocket bridge
  behavior.
- If WebSocket auth or destination behavior changes, add focused tests in
  `modules/notification-websocket`.

Frontend verification:

- Unit tests cover API client calls, SSE parser, STOMP frame creation/parsing,
  WebSocket URL derivation, and workbench notification request payloads.
- Typecheck, lint, and build must pass.
- A browser smoke check should run with backend and frontend dev servers on
  non-conflicting ports, then capture at least one REST publish exchange and one
  realtime event path when practical.

Full verification for the slice should include:

```bash
./gradlew :apps:api:test :modules:notification:test :modules:notification-sse:test :modules:notification-websocket:test
pnpm test
pnpm typecheck
pnpm lint
pnpm build
```

Commands are run in their respective repositories.

## Non-Goals

- Do not build a real chat product.
- Do not add persistent notification inbox storage.
- Do not add web push, mobile push, email, SMS, or Kakao 알림톡.
- Do not add Redis/Kafka fanout for this slice.
- Do not make WebFlux the server runtime.
- Do not make WebSocket dev-login implicit unless it is explicitly designed as a
  future auth enhancement.

## Acceptance Criteria

- A clean clone of both repositories can run backend and frontend locally with
  documented environment variables.
- The frontend can show module catalog, REST exchange logs, SSE status/events,
  and WebSocket status/events.
- Publishing a `demo` notification from the frontend produces a stable
  `ApiValueResponse<SkeletonNotificationPublishResponse>`.
- SSE receives the published event when connected to topic `demo`.
- WebSocket receives the published event when connected with a valid token.
- Logs and exchange panels expose trace id enough to correlate the flow.
- Swagger describes the REST publish contract with the standard response
  envelope.
