# Notification SSE Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional notification and SSE delivery modules that let applications push server events to web clients without carrying unused notification channels.

**Architecture:** `modules/notification` owns event DTOs, publisher/subscription contracts, and a replaceable in-memory broker. `modules/notification-sse` depends on `notification` and `platform`, exposes a Spring MVC `SseEmitter` endpoint, and contributes `permitAll` only when explicitly configured. The executable app does not depend on these modules by default.

**Tech Stack:** Kotlin 2.2.21, Java 21, Spring Boot 4.0.5 auto-configuration, Spring MVC `SseEmitter`, JUnit 5.

---

### Task 1: Register Modules

- [ ] Add `:modules:notification` and `:modules:notification-sse` to `settings.gradle.kts`.
- [ ] Create build files with module-local dependencies.
- [ ] Add Spring Boot auto-configuration import files.

### Task 2: Notification Core

- [ ] Write failing tests for event validation, topic filtering, close/unsubscribe, and delivery counts.
- [ ] Implement `NotificationEvent`, `NotificationSeverity`, `NotificationPublisher`, `NotificationPublishResult`, `NotificationSubscription`, `NotificationSubscriptionRegistry`, `NotificationBroker`, and `InMemoryNotificationBroker`.
- [ ] Add `NotificationAutoConfiguration` that provides an in-memory broker when no broker bean exists.
- [ ] Run `./gradlew :modules:notification:test` and confirm GREEN.

### Task 3: SSE Delivery

- [ ] Write failing tests for topic parsing, subscription cleanup, public endpoint contribution, and auto-configuration conditions.
- [ ] Implement `NotificationSseProperties`, `NotificationSseService`, `NotificationSseController`, and `NotificationSseAutoConfiguration`.
- [ ] Run `./gradlew :modules:notification-sse:test` and confirm GREEN.

### Task 4: Documentation

- [ ] Update README module layout and notification usage.
- [ ] Update CLAUDE module map and conventions.
- [ ] Update CHANGELOG.

### Task 5: Verification And Commit

- [ ] Run `git diff --check`.
- [ ] Run `./gradlew clean test`.
- [ ] Run `./gradlew :apps:api:bootJar`.
- [ ] Commit the complete notification SSE slice.
