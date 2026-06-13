# Persistence JPA

`modules:persistence-jpa` contains JPA-specific persistence conventions that are
useful across services but should stay optional by dependency.

## Audit Timestamps

Use `BaseJpaEntity` when an entity should carry standard audit timestamps:

```kotlin
@Entity
class OrderEntity : BaseJpaEntity() {
    @Id
    var id: Long? = null
}
```

The embedded `AuditTimestamps` stores:

- `created_at DATETIME(6)`
- `updated_at DATETIME(6)`
- `deleted_at DATETIME(6) nullable`

All timestamps use `Instant` and are truncated to MySQL microsecond precision.

## Optimistic Locking

Use `VersionedJpaEntity` when concurrent updates must be guarded by a version
column:

```kotlin
@Entity
class OrderEntity : VersionedJpaEntity() {
    @Id
    var id: Long? = null
}
```

This adds:

```kotlin
@Version
@Column(name = "version", nullable = false)
var version: Long? = null
```

Keep this opt-in. Not every table needs optimistic locking, and forcing a
version column into all entities makes lightweight lookup tables awkward.

## Partial Updates

Use `JpaPartialUpdateExecutor` when a command should update only selected
columns without loading the full entity.

```kotlin
val rows = jpaPartialUpdateExecutor.updateById(
    entityClass = OrderEntity::class.java,
    id = orderId,
    assignments = mapOf("status" to "PAID"),
    options = JpaPartialUpdateOptions(
        expectedVersion = currentVersion,
        incrementVersion = true,
        touchUpdatedAtPath = "audit.updatedAt",
    ),
)
```

Rules:

- Empty assignments are rejected.
- The id column cannot be updated through the helper.
- If `expectedVersion` is set, stale updates return `0` rows.
- If `incrementVersion=true`, the helper writes `version = expectedVersion + 1`.
- `touchUpdatedAtPath` can point to embedded audit paths such as
  `audit.updatedAt`.

This is for command-style updates. If domain invariants require loading the
aggregate, load the entity and use normal dirty checking instead.

## N+1 Guard

Use `JpaFetchGraphHints` to create standard JPA fetch graph hints:

```kotlin
val hints = jpaFetchGraphHints.fetchGraphHints(
    OrderEntity::class.java,
    "items",
    "items.product",
)

entityManager.find(OrderEntity::class.java, orderId, hints)
```

The helper uses the standard hint key:

```text
jakarta.persistence.fetchgraph
```

Prefer explicit fetch graphs for read paths that naturally need associations.
Keep broad eager mappings out of base entities.
