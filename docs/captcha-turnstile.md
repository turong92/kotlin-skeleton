# captcha-turnstile — Cloudflare Turnstile verification

```yaml
skeleton:
  captcha-turnstile:
    enabled: true                     # default false: TurnstileVerifier bean absent
    secret-key: ${TURNSTILE_SECRET_KEY}
    expected-hostname: ovation.example.com   # optional
    expected-action: submit-card             # optional, matches the widget's data-action
```

```kotlin
class CardController(private val turnstile: ObjectProvider<TurnstileVerifier>) {
    @PostMapping("/api/v1/cards")
    fun create(@RequestBody body: CreateCard, request: HttpServletRequest): ... {
        turnstile.ifAvailable { verifier ->
            val result = verifier.verify(body.turnstileToken, request.remoteAddr)
            if (!result.success) throw CaptchaFailedException(result.errorCodes)   // 400
        }
        ...
    }
}
```

Uses the platform outbound HTTP client (`skeleton.http.clients.turnstile.*` for timeouts, trace headers
propagate). Network failures return `success=false, errorCodes=["internal-error"]`, never throw.
