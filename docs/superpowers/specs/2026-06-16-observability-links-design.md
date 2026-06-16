# Observability Links Design

## Goal

Add a reusable observability link capability so logs, Slack alerts,
notification events, async failures, scheduler failures, payment failures, and
external HTTP failures can point operators or agents directly to the matching
trace/run/provider flow.

The skeleton should not assume one monitoring vendor. It should provide a
small standard contract and property-driven link templates that a service can
map to Grafana Loki, Kibana, CloudWatch Logs Insights, Datadog, Honeycomb, or a
private internal console.

## Why Now

The project already has:

- W3C trace context and MDC keys in `modules:platform`.
- Stable debug logger categories in `SkeletonLoggers`.
- Standard error response codes.
- Notification events and Slack forwarding.
- Async failure notification events.
- Payment failure notification events with provider trace fields.
- External HTTP provider trace extraction.

The missing piece is a consistent way to turn these fields into clickable
operator links.

## Module Boundary

Place the core capability in `modules:platform`.

`platform` already owns trace context, external HTTP, logging, web policy, and
error response contracts. Observability links are foundational metadata, not a
Slack-only concern. Other modules should consume the platform contract instead
of each defining their own URL format.

Do not create a separate vendor module in the first pass. Link generation is
template-based and vendor-neutral. Real vendor SDKs are unnecessary.

## Core Concepts

### `ObservabilityContext`

A small value object containing safe search fields:

- `traceId`
- `spanId`
- `parentSpanId`
- `runId`
- `accountId`
- `errorCode`
- `provider`
- `providerTraceId`
- `providerRequestId`
- `providerOperationId`
- `topic`
- `type`
- `route`
- `method`

Fields are optional. Blank fields are omitted.

The context can be created from:

- current MDC
- a `NotificationEvent`
- an exception/error code
- external HTTP trace metadata
- explicit app-provided fields

### `ObservabilityLink`

A generated link with:

- `id`: stable key such as `logs`, `trace`, `run`, `provider`
- `label`: display label such as `Logs by traceId`
- `url`: generated URL
- `kind`: `LOGS`, `TRACE`, `RUN`, `PROVIDER`, or `CUSTOM`

The public API should avoid leaking raw query DSL into callers. Callers ask a
resolver for links and decide where to render them.

### `ObservabilityLinkResolver`

The main interface:

```kotlin
fun interface ObservabilityLinkResolver {
    fun resolve(context: ObservabilityContext): List<ObservabilityLink>
}
```

The default implementation reads configured templates and expands placeholders
from the context.

### `ObservabilityContextContributor`

Optional contributor API for apps that need tenant, organization, plan,
region, deployment id, or domain-specific search fields without modifying
skeleton modules.

## Configuration

Use property namespace `skeleton.observability.links`.

Example:

```yaml
skeleton:
  observability:
    links:
      enabled: true
      templates:
        logs:
          label: Logs by traceId
          kind: LOGS
          url: "https://grafana.example/explore?query={traceId}"
          required-fields: [traceId]
        run:
          label: Flow by runId
          kind: RUN
          url: "https://ops.example/runs/{runId}"
          required-fields: [runId]
        provider:
          label: Provider request
          kind: PROVIDER
          url: "https://ops.example/providers/{provider}/requests/{providerRequestId}"
          required-fields: [provider, providerRequestId]
```

Defaults:

- `enabled=false`
- no templates
- missing required fields skip that template
- generated URLs are omitted when expansion would leave an unresolved
  placeholder

This keeps local/dev/prod startup safe when a company has no observability
vendor configured yet.

## Template Rules

Use named placeholders in braces:

```text
{traceId}
{runId}
{providerRequestId}
```

Expansion must URL-encode values. It must not encode the static parts of the
template.

Unknown placeholders make the template invalid at startup or skip the template
with a warning. Prefer fail-fast for invalid template syntax when
`enabled=true`, because broken links are configuration mistakes.

## Slack Integration

`modules:notification-slack` should consume `ObservabilityLinkResolver`.

`SlackAlert` should gain:

```kotlin
val links: List<SlackAlertLink> = emptyList()
```

or equivalent internal rendering support.

`SlackAlertMessageFactory` should render links as a Slack actions block or a
compact context line. The first pass should prefer a simple context line to
avoid complicated Slack block limits:

```text
Links: <https://...|Logs by traceId> · <https://...|Flow by runId>
```

`SlackExceptionAspect` should resolve links from current MDC and exception
metadata.

`SlackNotificationForwarder` should resolve links from `NotificationEvent`
payload plus current trace context. For async notification events, this means
`traceId`, `runId`, and `accountId` already become clickable if templates
exist.

Slack must still work when no resolver or no templates exist.

## Notification Integration

Do not mutate `NotificationEvent` in the first pass. Its payload is already the
portable event contract.

Instead, Slack and future channels can derive links while rendering. This keeps
SSE/WebSocket consumers from receiving vendor-specific URLs unless a later
feature explicitly wants that.

If FE workbench needs links later, expose a dedicated endpoint or add a
channel-specific DTO rather than forcing links into all notification events.

## Error Response Integration

Do not add links to `ApiError` by default.

Public API error bodies should remain stable, small, and safe. Diagnostic
links belong in logs, Slack alerts, dashboards, and internal workbench views.

Later, an internal profile may expose links through an admin-only debug
endpoint, but that is not part of this slice.

## External HTTP and Payment Integration

External HTTP already extracts provider trace data into `ExternalHttpTrace`.

Provider modules should add provider trace fields to notification payloads
when emitting failure events:

- `provider`
- `providerTraceId`
- `providerRequestId`
- `providerOperationId`

The payment sample already publishes several of these fields. The first
implementation should normalize field names enough that observability links can
resolve provider templates consistently.

When both `providerTraceId` and `providerRequestId` exist, templates choose the
field explicitly. The skeleton should not guess that one is a synonym for the
other because vendors use these identifiers differently. Provider modules can
populate both when they know the mapping.

## Security and Redaction

Do not include secrets, tokens, authorization headers, cookies, full request
bodies, raw provider bodies, or unbounded query strings in link contexts.

Allowed fields are identifiers intended for search or routing. Values are
URL-encoded and redacted before Slack rendering if they pass through general
field rendering.

Generated links should not contain credentials. If a vendor requires signed
URLs or temporary tokens, that belongs in an app-specific resolver override.

## Testing

### Platform Unit Tests

- Resolves a link when required fields are present.
- Skips a link when a required field is missing.
- URL-encodes placeholder values.
- Rejects or skips unknown placeholders.
- Omits blank context values.
- Allows app-provided resolver to override the default.

### Slack Tests

- Exception alert includes configured trace/run links when fields exist.
- Notification forwarding includes links derived from event payload fields.
- Slack payload remains valid when no links are produced.
- Link labels and URLs are escaped for Slack mrkdwn.
- Provider templates can choose `providerTraceId` or `providerRequestId`
  without implicit aliasing.

### App Composition Proof

`apps:api` should configure test-only observability templates and prove:

- `POST /api/v1/skeleton/async/fail` publishes an async failure event.
- Slack forwarding can render a link containing the request `traceId`.
- Existing smoke tests still pass when observability links are disabled.

## Acceptance Criteria

- A service can enable links using only properties.
- A service with no observability vendor sees no startup failure and no empty
  link noise.
- Slack exception alerts and Slack-forwarded notification events can include
  links.
- Async failure notifications gain trace/run links automatically when
  templates are configured.
- Error API responses remain unchanged.
- No secret-bearing values are used in generated links by default.
