# Notification Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the backend and React skeleton workbench prove the notification SSE/WebSocket smoke flow end to end.

**Architecture:** Keep the backend as the source of module contracts and REST/SSE/WebSocket endpoints. Keep the React app as a workbench that composes auth, REST exchange logging, SSE streaming, and STOMP WebSocket helpers without adding product-domain notification features. WebSocket smoke uses bearer JWT as the standard path; REST and SSE may still use dev-login headers in local/dev profiles.

**Tech Stack:** Kotlin, Spring Boot MVC, springdoc-openapi, MockMvc, React 19, TypeScript, Vite, Vitest, browser `fetch` streams, browser `WebSocket`, minimal STOMP frame helpers.

---

## File Structure

Backend repository:

- Modify `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt`: add OpenAPI operation metadata for the notification publish smoke endpoint.
- Modify `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`: assert the notification publish response envelope and Swagger summary.
- Modify `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SkeletonModuleCompositionIntegrationTest.kt`: assert module catalog includes WebSocket status and prove publish delivers to a `demo` subscriber.
- Modify `docs/notification-websocket.md`: replace loose smoke notes with a concrete backend/frontend workbench runbook.
- Modify `README.md`: add a short pointer to the notification workbench runbook if it is not already discoverable.

Frontend repository:

- Modify `src/modules/notifications/notificationStompSession.ts`: make the STOMP helper token-first and stop translating dev-login values into WebSocket auth headers.
- Modify `src/modules/notifications/notificationStompSession.test.ts`: lock the token-first behavior.
- Modify `src/routes/HomePage.tsx`: block WebSocket connect attempts without a bearer token in a visible exchange log/toast, keep SSE dev-login flow intact, and disable the WebSocket user-target publish button unless the WebSocket is open.
- Modify `README.md`: document the token-first WebSocket workbench flow and direct-backend port override.

Do not add chat, inbox persistence, Redis/Kafka fanout, mobile/web push, or vendor notification modules in this slice.

---

### Task 1: Backend Notification Publish Contract

**Files:**

- Modify: `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt`
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SkeletonModuleCompositionIntegrationTest.kt`

- [ ] **Step 1: Add failing OpenAPI and publish-delivery assertions**

In `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`, add this block after the existing JSON skeleton endpoint assertions and before item/page assertions:

```kotlin
        val notificationEnvelope = responseSchema(docs, "/api/v1/skeleton/notifications", "post", "200")
        val notificationProperties = properties(notificationEnvelope)
        assertTrue(notificationProperties.containsKey("value"))
        assertTrue(notificationProperties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/SkeletonNotificationPublishResponse",
            (notificationProperties["value"] as Map<*, *>)["\$ref"],
        )
        assertEquals(
            "#/components/schemas/SkeletonNotificationPublishRequest",
            JsonPath.read(
                docs,
                "$.paths['/api/v1/skeleton/notifications'].post.requestBody.content['application/json'].schema['\$ref']",
            ),
        )
        assertEquals(
            "Publish skeleton notification smoke event",
            JsonPath.read(docs, "$.paths['/api/v1/skeleton/notifications'].post.summary"),
        )
```

In `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SkeletonModuleCompositionIntegrationTest.kt`, add the WebSocket catalog assertion in `module catalog exposes composed runtime modules` near the existing notification assertions:

```kotlin
            jsonPath("$.values[?(@.id == 'notification-websocket')].status") { value(hasItem("DISABLED")) }
```

In the same test class, add this new test before `loginAccessToken()`:

```kotlin
    @Test
    fun `notification smoke endpoint publishes demo event to subscribers`() {
        val token = loginAccessToken()
        val events = Collections.synchronizedList(mutableListOf<NotificationEvent>())
        val latch = CountDownLatch(1)

        notificationSubscriptionRegistry.subscribe(
            topics = setOf("demo"),
            subscriber = NotificationSubscriber { event ->
                events += event
                latch.countDown()
            },
        ).use {
            mockMvc.post("/api/v1/skeleton/notifications") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                accept = MediaType.APPLICATION_JSON
                content = """
                    {
                      "topic": "demo",
                      "type": "frontend-smoke",
                      "severity": "INFO",
                      "title": "Frontend smoke",
                      "message": "React skeleton workbench ping",
                      "payload": {
                        "source": "react-skeleton",
                        "userId": "acc_user"
                      }
                    }
                """.trimIndent()
            }.andExpect {
                status { isOk() }
                jsonPath("$.value.topic") { value("demo") }
                jsonPath("$.value.type") { value("frontend-smoke") }
                jsonPath("$.value.deliveredSubscribers") { value(1) }
                jsonPath("$.meta.traceId") { isNotEmpty() }
            }

            assertTrue(latch.await(3, TimeUnit.SECONDS))
        }

        val event = events.single()
        assertTrue(event.topic == "demo")
        assertTrue(event.type == "frontend-smoke")
        assertTrue(event.payload["source"] == "react-skeleton")
        assertTrue(event.payload["userId"] == "acc_user")
    }
```

- [ ] **Step 2: Run backend tests to verify RED**

Run from `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton`:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*OpenApiDocumentationIntegrationTest*' --tests '*SkeletonModuleCompositionIntegrationTest*' --rerun-tasks
```

Expected: `OpenApiDocumentationIntegrationTest` fails because the notification publish operation summary is not yet set to `Publish skeleton notification smoke event`. If the publish-delivery test already passes, keep it; it is the regression proof for the workbench path.

- [ ] **Step 3: Add notification publish OpenAPI metadata**

In `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt`, add these imports:

```kotlin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse
```

Add these annotations directly above `@PostMapping("/notifications", ...)`:

```kotlin
    @Operation(
        summary = "Publish skeleton notification smoke event",
        description = "Publishes a notification through the composed notification module so SSE, WebSocket, Slack, or other subscribers can verify delivery.",
    )
    @OpenApiResponse(responseCode = "200", description = "Published notification event")
```

The method remains:

```kotlin
    @PostMapping(
        "/notifications",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun publishNotification(
        @Valid @RequestBody request: SkeletonNotificationPublishRequest,
    ) = NotificationEvent(
        topic = request.topic,
        type = request.type,
        severity = request.severity,
        title = request.title,
        message = request.message,
        payload = request.payload,
    ).let { event ->
        val result = notificationPublisher.getObject().publish(event)
        Response.ok(
            SkeletonNotificationPublishResponse(
                eventId = result.eventId,
                topic = event.topic,
                type = event.type,
                deliveredSubscribers = result.deliveredSubscribers,
            ),
        )
    }
```

- [ ] **Step 4: Run backend tests to verify GREEN**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*OpenApiDocumentationIntegrationTest*' --tests '*SkeletonModuleCompositionIntegrationTest*' --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit backend contract changes**

Run:

```bash
git add apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt \
  apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt \
  apps/api/src/test/kotlin/dev/sumin/skeleton/api/SkeletonModuleCompositionIntegrationTest.kt
git commit -m "feat: document notification workbench contract"
```

---

### Task 2: Frontend Token-First Realtime Workbench

**Files:**

- Modify: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton/src/modules/notifications/notificationStompSession.ts`
- Modify: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton/src/modules/notifications/notificationStompSession.test.ts`
- Modify: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton/src/routes/HomePage.tsx`

- [ ] **Step 1: Add failing STOMP helper assertions**

In `src/modules/notifications/notificationStompSession.test.ts`, replace the test named `falls back to dev login STOMP headers when access token is absent` with:

```ts
  it('does not translate dev login values into websocket authentication headers', () => {
    expect(
      createNotificationConnectFrame('wss://api.example.com/ws/notifications', {
        accessToken: '',
        devLogin: { accountId: 'acc_user', email: 'user@example.com' },
      }),
    ).toEqual({
      command: 'CONNECT',
      headers: {
        'accept-version': '1.2',
        'heart-beat': '10000,10000',
        host: 'api.example.com',
      },
    })
  })
```

Add this import and test near the existing imports/tests:

```ts
import {
  canConnectNotificationWebSocket,
  createNotificationConnectFrame,
  createNotificationSubscribeFrames,
  parseNotificationMessage,
} from './notificationStompSession'
```

```ts
  it('requires a non-empty bearer token for the standard websocket smoke flow', () => {
    expect(canConnectNotificationWebSocket('')).toBe(false)
    expect(canConnectNotificationWebSocket('   ')).toBe(false)
    expect(canConnectNotificationWebSocket('token-1')).toBe(true)
    expect(canConnectNotificationWebSocket('Bearer token-1')).toBe(true)
  })
```

- [ ] **Step 2: Run frontend tests to verify RED**

Run from `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton`:

```bash
pnpm test -- src/modules/notifications/notificationStompSession.test.ts
```

Expected: FAIL because `canConnectNotificationWebSocket` does not exist and the helper still emits `X-Dev-*` headers.

- [ ] **Step 3: Implement token-first STOMP helper**

In `src/modules/notifications/notificationStompSession.ts`, make `devLogin` optional for call-site compatibility, add the helper, and replace `authHeaders`:

```ts
export type NotificationStompAuth = {
  accessToken: string
  devLogin?: DevLoginIdentity
}

export function canConnectNotificationWebSocket(accessToken: string): boolean {
  return accessToken.trim().length > 0
}

function authHeaders(auth: NotificationStompAuth): Record<string, string> {
  const accessToken = auth.accessToken.trim()
  if (!accessToken) return {}
  return {
    Authorization: accessToken.startsWith('Bearer ') ? accessToken : `Bearer ${accessToken}`,
  }
}
```

Do not send `X-Dev-Account-Id`, `X-Dev-Username`, or `X-Dev-Email` in STOMP `CONNECT`. REST and SSE still use dev-login through `applyAuthHeaders`.

- [ ] **Step 4: Update the React workbench WebSocket flow**

In `src/routes/HomePage.tsx`, add `canConnectNotificationWebSocket` to the existing notification import:

```ts
import {
  canConnectNotificationWebSocket,
  createNotificationConnectFrame,
  createNotificationSubscribeFrames,
  parseNotificationMessage,
  type NotificationStompMessage,
} from '../modules/notifications/notificationStompSession'
```

At the top of `startWebSocket()`, immediately after `stopWebSocket()`, add this guard:

```ts
    if (!canConnectNotificationWebSocket(accessToken)) {
      const traceContext = createTraceContext(flowTraceId)
      setWebSocketStatus('error')
      toast.error('websocket requires bearer token')
      pushExchange({
        label: 'notifications.websocket.blocked',
        method: 'CONNECT',
        path: '/ws/notifications',
        status: undefined,
        durationMs: 0,
        traceId: traceContext.traceId,
        request: {
          headers: {
            traceparent: traceContext.traceparent,
            'X-Trace-Id': traceContext.traceId,
          },
        },
        error: {
          code: 'MISSING_WEBSOCKET_TOKEN',
          message: 'Run auth.login before opening the WebSocket smoke connection.',
        },
      })
      return
    }
```

In the `createNotificationConnectFrame` call, pass only `accessToken`:

```ts
    const connectFrame = createNotificationConnectFrame(webSocketUrl, {
      accessToken,
    })
```

In `callWebSocketNotificationSmoke()`, keep user-target delivery tied to the bearer principal:

```ts
    const userId = activePrincipal?.accountId ?? 'acc_user'
```

In the WebSocket `publish` button, disable it unless the WebSocket is open:

```tsx
                <ActionButton
                  label="publish"
                  icon={<Bell size={16} />}
                  disabled={webSocketStatus !== 'open'}
                  busy={busyAction === 'skeleton.websocket-notification'}
                  onClick={callWebSocketNotificationSmoke}
                />
```

- [ ] **Step 5: Run frontend focused tests to verify GREEN**

Run:

```bash
pnpm test -- src/modules/notifications/notificationStompSession.test.ts
```

Expected: `Test Files 1 passed`.

- [ ] **Step 6: Run frontend typecheck**

Run:

```bash
pnpm typecheck
```

Expected: exits with code 0.

- [ ] **Step 7: Commit frontend token-first changes**

Run:

```bash
git add src/modules/notifications/notificationStompSession.ts \
  src/modules/notifications/notificationStompSession.test.ts \
  src/routes/HomePage.tsx
git commit -m "feat: harden notification workbench websocket flow"
```

---

### Task 3: Workbench Runbook Documentation

**Files:**

- Modify: `docs/notification-websocket.md`
- Modify: `README.md`
- Modify: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton/README.md`

- [ ] **Step 1: Update backend WebSocket notification runbook**

In `docs/notification-websocket.md`, keep the existing module explanation and replace the `Smoke Flow` section with:

````markdown
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
pnpm dev -- --host 127.0.0.1 --port 5173
```

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
````

- [ ] **Step 2: Add backend README pointer**

In `README.md`, add this bullet near existing documentation links or workbench notes:

```markdown
- Realtime notification workbench: `docs/notification-websocket.md` shows how to run `apps/api` with SSE/WebSocket enabled and verify it from `react-skeleton`.
```

- [ ] **Step 3: Add frontend README workbench notes**

In `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton/README.md`, update the `워크벤치` list so it includes:

```markdown
- `GET /notifications/sse?topic=demo` fetch streaming 연결
- `/ws/notifications` STOMP WebSocket 연결. WebSocket 인증을 켠 백엔드는 `auth.login`으로 받은 bearer token이 필요하고 dev-login 헤더는 REST/SSE 전용으로 취급
- `POST /skeleton/notifications` publish 후 SSE/WebSocket 이벤트 수신 확인
```

Add this paragraph under `백엔드 연결`:

```markdown
WebSocket URL은 `VITE_API_BASE_URL`에서 자동 파생된다. 예를 들어 `VITE_API_BASE_URL=http://localhost:18080/api/v1`이면 WebSocket은 `ws://localhost:18080/ws/notifications`로 연결한다.
```

- [ ] **Step 4: Run documentation checks**

Run from the backend repository:

```bash
git diff --check
```

Run from the frontend repository:

```bash
git diff --check
```

Expected: both commands exit with code 0.

- [ ] **Step 5: Commit backend docs**

Run from `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton`:

```bash
git add docs/notification-websocket.md README.md
git commit -m "docs: add notification workbench runbook"
```

- [ ] **Step 6: Commit frontend docs**

Run from `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton`:

```bash
git add README.md
git commit -m "docs: document notification workbench flow"
```

---

### Task 4: Full Verification And Browser Smoke

**Files:**

- Read: `docs/notification-websocket.md`
- Read: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton/README.md`
- No expected source changes unless verification reveals a defect.

- [ ] **Step 1: Run backend affected tests**

Run from `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton`:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test :modules:notification:test :modules:notification-sse:test :modules:notification-websocket:test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run frontend verification**

Run from `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton`:

```bash
pnpm test
pnpm typecheck
pnpm lint
pnpm build
```

Expected: all commands exit with code 0.

- [ ] **Step 3: Start backend smoke server**

Use a port that does not conflict with the user's known occupied ports `8081`,
`3000`, and `8002`. Prefer 8080; if it is occupied, use 18080.

8080 command:

```bash
SPRING_PROFILES_ACTIVE=local \
SKELETON_CONFIG_AWS_SSM_FAIL_FAST=false \
SKELETON_REDIS_LOCK_ENABLED=false \
SKELETON_NOTIFICATION_WEBSOCKET_ENABLED=true \
SKELETON_NOTIFICATION_WEBSOCKET_AUTH_ENABLED=true \
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem \
./gradlew :apps:api:bootRun
```

18080 command:

```bash
SPRING_PROFILES_ACTIVE=local \
SERVER_PORT=18080 \
SKELETON_CONFIG_AWS_SSM_FAIL_FAST=false \
SKELETON_REDIS_LOCK_ENABLED=false \
SKELETON_NOTIFICATION_WEBSOCKET_ENABLED=true \
SKELETON_NOTIFICATION_WEBSOCKET_AUTH_ENABLED=true \
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem \
./gradlew :apps:api:bootRun
```

Expected: application starts and logs the local port.

- [ ] **Step 4: Start frontend smoke server**

If backend is on 8080, run:

```bash
pnpm dev -- --host 127.0.0.1 --port 5173
```

If backend is on 18080, run:

```bash
VITE_API_BASE_URL=http://localhost:18080/api/v1 pnpm dev -- --host 127.0.0.1 --port 5174
```

Expected: Vite reports the local URL.

- [ ] **Step 5: Browser smoke path**

Open the frontend URL in the browser and execute this path:

1. Click `auth.login`.
2. Click `sse`.
3. Click `notify`.
4. Confirm at least one SSE event appears.
5. Click `ws`.
6. Confirm WebSocket status becomes `open`.
7. Click WebSocket `publish`.
8. Confirm at least one WebSocket event appears.
9. Copy one exchange log entry and confirm it includes `traceId`.

Expected: REST publish response is visible, SSE receives a `demo` event, WebSocket receives a `demo` event, and trace ids are visible in the exchange log.

- [ ] **Step 6: Capture smoke evidence in final notes**

Record these in the implementation final response:

```text
Backend tests: include the exact Gradle command and the BUILD SUCCESSFUL summary.
Frontend verification: include the exact pnpm commands and their pass summaries.
Backend URL: include the actual backend URL used during smoke.
Frontend URL: include the actual frontend URL used during smoke.
Smoke result: state whether auth.login, SSE, notify, ws, and ws publish were observed.
Representative traceId: include one trace id copied from the exchange log.
```

- [ ] **Step 7: Final git state check**

Run from both repositories:

```bash
git status --short
git log --oneline -5
```

Expected: no uncommitted changes. Recent commits include the backend contract/docs commits and frontend realtime/docs commits.

---

## Self-Review

- Spec coverage: backend publish contract, module catalog, token-first WebSocket auth, SSE fetch streaming, REST exchange logs, documentation, and browser smoke are covered by Tasks 1-4.
- Placeholder scan: no `TBD`, `TODO`, or "add appropriate" placeholders remain.
- Type consistency: plan uses existing names `SkeletonNotificationPublishRequest`, `SkeletonNotificationPublishResponse`, `NotificationEvent`, `NotificationStompAuth`, `canConnectNotificationWebSocket`, `WebSocketStatus`, `ApiValueResponse`, and `/api/v1/skeleton/notifications`.
