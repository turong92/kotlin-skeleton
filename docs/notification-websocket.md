# Notification WebSocket

`modules:notification-websocket` adds optional STOMP delivery on top of the
base `notification` module. It is intentionally separate from SSE and Slack so
services can attach only the realtime channel they need.

## Enable

For the sample app:

```bash
SPRING_PROFILES_ACTIVE=local
SKELETON_NOTIFICATION_WEBSOCKET_ENABLED=true
SKELETON_NOTIFICATION_WEBSOCKET_AUTH_ENABLED=true
```

The default endpoint is:

```text
/ws/notifications
```

The default local allowed origins are:

```text
http://localhost:[*],http://127.0.0.1:[*]
```

## Authentication

When `SKELETON_NOTIFICATION_WEBSOCKET_AUTH_ENABLED=true`, the client must send a
bearer token on the STOMP `CONNECT` frame.

```text
CONNECT
accept-version:1.2
heart-beat:10000,10000
Authorization:Bearer <access-token>

^@
```

`apps/api` wires `JwtTokenService` to `NotificationWebSocketTokenVerifier` when
both `auth` and `notification-websocket` are present. The resulting WebSocket
`Principal.name` is the JWT subject, which is the account id.

If authentication is disabled, topic delivery still works. User destination
delivery requires a session `Principal`, so enable WebSocket auth or provide a
custom `NotificationWebSocketTokenVerifier`/handshake principal in the app.

## Subscribe

Subscribe to the public topic channel:

```text
SUBSCRIBE
id:notifications-topic-demo
destination:/topic/notifications/demo
ack:auto

^@
```

Subscribe to the current user's private channel:

```text
SUBSCRIBE
id:notifications-user
destination:/user/queue/notifications
ack:auto

^@
```

The module publishes every event to:

```text
/topic/notifications/{topic}
```

If `event.payload.userId` is present, it also publishes to:

```text
/user/{userId}/queue/notifications
```

Clients subscribe through Spring's user prefix as `/user/queue/notifications`.

## Workbench Smoke Flow

Start the backend from the Kotlin skeleton repository.

Default proxy-friendly port:

```bash
SPRING_PROFILES_ACTIVE=local \
SKELETON_CONFIG_AWS_SSM_FAIL_FAST=false \
SKELETON_REDIS_LOCK_ENABLED=false \
SKELETON_NOTIFICATION_WEBSOCKET_ENABLED=true \
SKELETON_NOTIFICATION_WEBSOCKET_AUTH_ENABLED=true \
./gradlew :apps:api:bootRun
```

Alternative backend port when 8080 is occupied:

```bash
SPRING_PROFILES_ACTIVE=local \
SERVER_PORT=18080 \
SKELETON_CONFIG_AWS_SSM_FAIL_FAST=false \
SKELETON_REDIS_LOCK_ENABLED=false \
SKELETON_NOTIFICATION_WEBSOCKET_ENABLED=true \
SKELETON_NOTIFICATION_WEBSOCKET_AUTH_ENABLED=true \
./gradlew :apps:api:bootRun
```

Start the frontend from the React skeleton repository.

With the backend on 8080:

```bash
VITE_API_BASE_URL=http://localhost:8080/api/v1 \
pnpm dev -- --host 127.0.0.1 --port 5173
```

Set `VITE_API_BASE_URL` even when the backend uses 8080 so REST, SSE, and
WebSocket derive from the same backend origin. The Vite proxy only covers
`/api/v1/*`; it does not proxy `/ws/notifications`.

With the backend on 18080:

```bash
VITE_API_BASE_URL=http://localhost:18080/api/v1 \
pnpm dev -- --host 127.0.0.1 --port 5174
```

In the workbench:

1. Run `auth.login` and capture the bearer token.
2. Open `sse`; this may use bearer token or dev-login headers.
3. Open `ws`; this requires the bearer token because STOMP `CONNECT` uses JWT
   verification.
4. Click `notify` to publish a topic event for SSE.
5. Click WebSocket `publish` after `ws` is open to publish a topic and
   user-targeted event.
6. Use the exchange log `traceId` to search backend logs for the same flow.

Expected results:

- `POST /api/v1/skeleton/notifications` returns
  `ApiValueResponse<SkeletonNotificationPublishResponse>`.
- The SSE panel receives the event for topic `demo`.
- The WebSocket panel receives the event on `/topic/notifications/demo`.
- If the payload `userId` matches the JWT subject, the WebSocket user
  subscription can also receive the event on `/user/queue/notifications`.
