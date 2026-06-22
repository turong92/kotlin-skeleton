# Event Outbox JDBC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional JDBC transactional outbox module for `KafkaEvent` so event publication survives process crashes and Kafka outages after the business transaction commits.

**Architecture:** Keep the existing `event-kafka` contracts as the public API. Add `modules/event-outbox-jdbc` that overrides `KafkaEventPublisher` with a DB-backed publisher, stores `KafkaEvent` rows in `skeleton_event_outbox`, and exposes an `OutboxEventDispatcher` that sends due rows through the existing `KafkaEventSender` and `KafkaEventMessageFactory`.

**Tech Stack:** Kotlin, Spring Boot auto-configuration, Spring JDBC `NamedParameterJdbcTemplate`, Flyway SQL resource, existing `JsonCodec`, existing `KafkaEvent` contracts.

## Global Constraints

- Keep `event-kafka` source-compatible: app code still calls `KafkaEventPublisher.publish(KafkaEvent)`.
- Keep outbox optional: applications include `modules:event-outbox-jdbc` only when they want DB-backed dispatch.
- Use TDD: each production behavior starts with a failing test.
- Store outbox payload and headers as JSON strings using the existing `JsonCodec`.
- The first implementation handles JDBC persistence, retry state, manual dispatch, and app composition. Scheduler wiring can be added next.

---

### Task 1: Add Outbox Domain and JDBC Repository

**Files:**
- Create: `modules/event-outbox-jdbc/build.gradle.kts`
- Create: `modules/event-outbox-jdbc/src/main/kotlin/dev/sumin/skeleton/event/outbox/jdbc/OutboxEvent.kt`
- Create: `modules/event-outbox-jdbc/src/main/kotlin/dev/sumin/skeleton/event/outbox/jdbc/JdbcOutboxEventRepository.kt`
- Create: `modules/event-outbox-jdbc/src/main/resources/db/migration/V2026062201__event_outbox.sql`
- Test: `modules/event-outbox-jdbc/src/test/kotlin/dev/sumin/skeleton/event/outbox/jdbc/JdbcOutboxEventRepositoryTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `KafkaEvent`, `JsonCodec`, `NamedParameterJdbcTemplate`
- Produces: `OutboxEventRepository.append(event: KafkaEvent): OutboxEventRecord`, `findDue(now: Instant, limit: Int): List<OutboxEventRecord>`, `markPublished(id: String, publishedAt: Instant)`, `markFailed(id: String, failedAt: Instant, nextAvailableAt: Instant, lastError: String)`

- [x] **Step 1: Write failing repository test**

```kotlin
@Test
fun `appends kafka event and finds it as due pending row`() {
    val repository = JdbcOutboxEventRepository(jdbc, JacksonJsonCodec())
    val event = KafkaEvent(
        topic = "payments",
        type = "payment.approved",
        id = "event-1",
        payload = mapOf("paymentId" to "payment-1"),
        headers = mapOf("source" to "test"),
        createdAt = Instant.parse("2026-06-22T00:00:00Z"),
    )

    val saved = repository.append(event)
    val due = repository.findDue(Instant.parse("2026-06-22T00:00:01Z"), 10)

    assertThat(saved.eventId).isEqualTo("event-1")
    assertThat(due).hasSize(1)
    assertThat(due.single().event.payload).isEqualTo(mapOf("paymentId" to "payment-1"))
    assertThat(due.single().event.headers).containsEntry("source", "test")
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:event-outbox-jdbc:test --tests '*JdbcOutboxEventRepositoryTest'`

Expected: FAIL because `event-outbox-jdbc` module/classes do not exist.

- [x] **Step 3: Implement repository and migration**

Create `OutboxEventStatus`, `OutboxEventRecord`, `OutboxEventRepository`, and JDBC implementation. Use primary key `id`, unique key `event_id`, status values `PENDING`, `PUBLISHED`, `FAILED`, and retry fields `attempts`, `available_at`, `last_error`.

- [x] **Step 4: Run repository test to verify it passes**

Run: `JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:event-outbox-jdbc:test --tests '*JdbcOutboxEventRepositoryTest'`

Expected: PASS.

### Task 2: Add Outbox Publisher and Dispatcher

**Files:**
- Create: `modules/event-outbox-jdbc/src/main/kotlin/dev/sumin/skeleton/event/outbox/jdbc/OutboxKafkaEventPublisher.kt`
- Create: `modules/event-outbox-jdbc/src/main/kotlin/dev/sumin/skeleton/event/outbox/jdbc/OutboxEventDispatcher.kt`
- Test: `modules/event-outbox-jdbc/src/test/kotlin/dev/sumin/skeleton/event/outbox/jdbc/OutboxKafkaEventPublisherTest.kt`
- Test: `modules/event-outbox-jdbc/src/test/kotlin/dev/sumin/skeleton/event/outbox/jdbc/OutboxEventDispatcherTest.kt`

**Interfaces:**
- Consumes: `OutboxEventRepository`, `KafkaEventSender`, `KafkaEventMessageFactory`
- Produces: `OutboxKafkaEventPublisher : KafkaEventPublisher`, `OutboxEventDispatcher.dispatchDue(now: Instant): OutboxDispatchResult`

- [x] **Step 1: Write failing publisher test**

```kotlin
@Test
fun `publisher stores event in outbox without sending immediately`() {
    val repository = RecordingOutboxEventRepository()
    val publisher = OutboxKafkaEventPublisher(repository)

    val result = publisher.publish(KafkaEvent(topic = "payments", type = "created", id = "event-1"))

    assertThat(result.eventId).isEqualTo("event-1")
    assertThat(repository.appended.single().id).isEqualTo("event-1")
}
```

- [x] **Step 2: Write failing dispatcher test**

```kotlin
@Test
fun `dispatcher sends due events and marks them published`() {
    val repository = RecordingOutboxEventRepository(
        due = listOf(recordFor(KafkaEvent(topic = "payments", type = "created", id = "event-1"))),
    )
    val sender = RecordingKafkaEventSender()
    val dispatcher = OutboxEventDispatcher(repository, sender, messageFactory, EventOutboxJdbcProperties())

    val result = dispatcher.dispatchDue(Instant.parse("2026-06-22T00:00:00Z"))

    assertThat(sender.sent).hasSize(1)
    assertThat(repository.publishedIds).containsExactly("outbox-1")
    assertThat(result.published).isEqualTo(1)
}
```

- [x] **Step 3: Implement publisher and dispatcher**

Publisher calls `repository.append(event)` and returns `KafkaEventPublishResult`. Dispatcher reads due records, calls `sender.send(messageFactory.toMessage(record.event))`, marks success, and marks failure with `availableAt + retryBackoff`.

- [x] **Step 4: Run module tests**

Run: `JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:event-outbox-jdbc:test`

Expected: PASS.

### Task 3: Add Auto-Configuration and App Composition

**Files:**
- Create: `modules/event-outbox-jdbc/src/main/kotlin/dev/sumin/skeleton/event/outbox/jdbc/EventOutboxJdbcAutoConfiguration.kt`
- Create: `modules/event-outbox-jdbc/src/main/kotlin/dev/sumin/skeleton/event/outbox/jdbc/EventOutboxJdbcProperties.kt`
- Create: `modules/event-outbox-jdbc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `modules/event-outbox-jdbc/src/test/kotlin/dev/sumin/skeleton/event/outbox/jdbc/EventOutboxJdbcAutoConfigurationTest.kt`
- Modify: `apps/api/build.gradle.kts`
- Modify: `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt`
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SkeletonModuleCompositionIntegrationTest.kt`

**Interfaces:**
- Consumes: `DataSource`, `JsonCodec`, `KafkaEventSender`, `KafkaEventMessageFactory`
- Produces: `OutboxEventRepository`, `KafkaEventPublisher`, `OutboxEventDispatcher`

- [x] **Step 1: Write failing auto-configuration test**

```kotlin
@Test
fun `auto configuration replaces application event kafka publisher with outbox publisher`() {
    contextRunner.run { context ->
        assertThat(context).hasSingleBean(KafkaEventPublisher::class.java)
        assertThat(context.getBean(KafkaEventPublisher::class.java)).isInstanceOf(OutboxKafkaEventPublisher::class.java)
        assertThat(context).hasSingleBean(OutboxEventDispatcher::class.java)
    }
}
```

- [x] **Step 2: Implement auto-configuration**

Register repository, publisher, and dispatcher when a `DataSource` exists. Order auto-configuration before `EventKafkaAutoConfiguration` for the publisher override and after data/json auto-configuration for dependencies.

- [x] **Step 3: Add app dependency and module catalog entry**

Add `implementation(project(":modules:event-outbox-jdbc"))` to `apps/api`, then expose `event-outbox-jdbc` in `/api/v1/skeleton/modules`.

- [x] **Step 4: Run app composition tests**

Run: `JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*SkeletonModuleCompositionIntegrationTest*'`

Expected: PASS and module catalog includes `event-outbox-jdbc`.

### Task 4: Final Verification and Commit

**Files:**
- All files touched in Tasks 1-3

**Interfaces:**
- Consumes: completed implementation
- Produces: committed feature

- [x] **Step 1: Run focused tests**

Run: `JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:event-outbox-jdbc:test :modules:event-kafka:test :apps:api:test --rerun-tasks`

Expected: PASS.

- [x] **Step 2: Run diff checks**

Run: `git diff --check`

Expected: no output.

- [x] **Step 3: Commit**

```bash
git add settings.gradle.kts apps/api/build.gradle.kts apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt apps/api/src/test/kotlin/dev/sumin/skeleton/api/SkeletonModuleCompositionIntegrationTest.kt docs/superpowers/plans/2026-06-22-event-outbox-jdbc.md modules/event-outbox-jdbc
git commit -m "feat: add jdbc event outbox"
```
