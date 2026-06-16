# Observability Links

`modules:platform` provides `ObservabilityLinkResolver`, a vendor-neutral way
to turn safe identifiers such as `traceId`, `runId`, and provider request ids
into clickable operator links.

## Configuration

Links are disabled by default.

```yaml
skeleton:
  observability:
    links:
      enabled: true
      templates:
        logs:
          label: Logs by traceId
          kind: LOGS
          url: "https://grafana.example/explore?traceId={traceId}"
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

Template values are URL-encoded. Templates are skipped when required fields are
missing. Unknown placeholders fail startup when links are enabled.

## Slack Alerts

`modules:notification-slack` consumes the resolver automatically. Exception
alerts and forwarded notification events render links when templates can be
resolved from MDC or event payload fields.

## API Errors

API error responses do not include links. Keep public errors stable and expose
diagnostic links through Slack, logs, dashboards, or internal workbench views.
