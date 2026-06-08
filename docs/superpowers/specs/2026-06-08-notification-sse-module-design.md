# Notification SSE Module Design

## Goal

Add notifications as a composable skeleton capability without forcing every service to carry web push, Slack, email, SMS, or SSE code.

This slice builds:

- `modules/notification`: provider-neutral notification event contracts and a local in-memory broker.
- `modules/notification-sse`: an optional Spring MVC SSE endpoint for pushing server notifications to web clients.

External vendor channels such as Slack, email, SMS, Kakao 알림톡, and Web Push stay out of this slice and can become separate optional modules later.

## Module Layout

```text
modules/
  notification/
    core event DTOs
    publisher contract
    subscription registry contract
    in-memory broker default

  notification-sse/
    SseEmitter connection service
    GET /api/v1/notifications/sse endpoint
    optional public-endpoint contribution
```

`apps/api` does not depend on either module by default. Applications opt in by adding `modules/notification` and then any delivery modules they need.

## Contracts

`NotificationEvent` is the stable outbound message shape:

- `id`: event id. Defaults to a random UUID string.
- `topic`: routing key such as `system`, `orders`, `runs`, or `account:{id}`.
- `type`: event type such as `created`, `progress`, `completed`, or `failed`.
- `severity`: `INFO`, `SUCCESS`, `WARNING`, or `ERROR`.
- `title`, `message`: optional human-readable text.
- `payload`: optional structured data.
- `createdAt`: event timestamp.

`NotificationPublisher` publishes an event and returns `NotificationPublishResult` with the event id and number of local subscribers that received it.

`NotificationSubscriptionRegistry` subscribes a callback to one or more topics. An empty topic set means "all topics" and is useful for local dashboards or agent tooling.

The default `InMemoryNotificationBroker` is single-node only. It is a skeleton default, not a distributed delivery guarantee. Real apps can replace it with Redis, Kafka, database-backed fanout, or a vendor-specific module.

## SSE Delivery

`notification-sse` uses Spring MVC `SseEmitter`, not a WebFlux server. The backend remains MVC; WebFlux is still only an outbound HTTP runtime in this skeleton.

Endpoint:

```text
GET /api/v1/notifications/sse?topic=runs&topic=orders
```

Behavior:

- Produces `text/event-stream`.
- Registers an SSE connection with the common `NotificationSubscriptionRegistry`.
- Sends each notification as an SSE event whose id is `NotificationEvent.id`, name is `NotificationEvent.type`, and data is the full `NotificationEvent`.
- Sends an initial `connected` event so clients can confirm the stream is alive.
- Closes the broker subscription on completion, timeout, or error.

Security:

- The SSE endpoint is not public by default.
- If an app needs browser `EventSource` without bearer headers, it can enable `skeleton.notification.sse.public-endpoint=true` and then handle access with cookies, gateway policy, or a scoped topic token.

## Configuration

```yaml
skeleton:
  notification:
    sse:
      enabled: true
      timeout: 30m
      public-endpoint: false
```

Because `notification-sse` is an optional module, `enabled=true` is acceptable as the module-local default. If the dependency is not present, no SSE endpoint exists.

## Testing

- `notification` tests cover event validation, topic filtering, close/unsubscribe behavior, and delivered subscriber counts.
- `notification-sse` tests cover topic parsing, subscription cleanup on emitter completion, public endpoint contribution, and auto-configuration enable/disable behavior.
- Root verification must run `git diff --check`, `./gradlew clean test`, and `./gradlew :apps:api:bootJar`.
