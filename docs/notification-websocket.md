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

## Smoke Flow

1. Start `apps/api` with WebSocket enabled.
2. Get a JWT from `POST /api/v1/auth/login`.
3. Open a STOMP WebSocket connection to `/ws/notifications`.
4. Subscribe to `/topic/notifications/demo` and `/user/queue/notifications`.
5. Publish a smoke event:

```http
POST /api/v1/skeleton/notifications
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "topic": "demo",
  "type": "frontend-websocket-smoke",
  "severity": "INFO",
  "title": "WebSocket smoke",
  "message": "React skeleton WebSocket ping",
  "payload": {
    "source": "react-skeleton",
    "userId": "acc_user"
  }
}
```

Expected result:

- `/topic/notifications/demo` receives the event.
- `/user/queue/notifications` receives the same event when the connected JWT
  subject is `acc_user`.

The React skeleton workbench exposes the same flow in the Realtime panel:

- `ws`: connect and subscribe.
- `publish`: publish a `demo` notification with `payload.userId`.
- The latest WebSocket messages appear under the WebSocket event log.
