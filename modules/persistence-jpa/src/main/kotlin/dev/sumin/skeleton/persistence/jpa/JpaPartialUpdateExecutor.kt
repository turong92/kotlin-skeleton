package dev.sumin.skeleton.persistence.jpa

import dev.sumin.skeleton.common.time.TimeProvider
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaUpdate
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Root

data class JpaPartialUpdateOptions(
    val idAttribute: String = "id",
    val expectedVersion: Long? = null,
    val versionAttribute: String = "version",
    val incrementVersion: Boolean = expectedVersion != null,
    val touchUpdatedAtPath: String? = null,
    val timeProvider: TimeProvider = TimeProvider.systemUtc(),
)

class JpaPartialUpdateExecutor(
    private val entityManager: EntityManager,
) {
    fun <E : Any, ID : Any> updateById(
        entityClass: Class<E>,
        id: ID,
        assignments: Map<String, Any?>,
        options: JpaPartialUpdateOptions = JpaPartialUpdateOptions(),
    ): Int {
        require(assignments.isNotEmpty()) {
            "At least one assignment is required for partial update"
        }
        require(options.idAttribute !in assignments.keys) {
            "Id attribute '${options.idAttribute}' cannot be updated through partial update"
        }
        require(options.expectedVersion == null || options.versionAttribute !in assignments.keys) {
            "Version attribute '${options.versionAttribute}' is controlled by JpaPartialUpdateOptions"
        }

        val criteriaBuilder = entityManager.criteriaBuilder
        val update = criteriaBuilder.createCriteriaUpdate(entityClass)
        val root = update.from(entityClass)

        assignments.forEach { (attributePath, value) ->
            update.setPath(root, attributePath, value)
        }
        options.touchUpdatedAtPath?.let { attributePath ->
            update.setPath(root, attributePath, options.timeProvider.now())
        }
        if (options.expectedVersion != null && options.incrementVersion) {
            update.setPath(root, options.versionAttribute, options.expectedVersion + 1)
        }

        update.where(
            criteriaBuilder.idAndVersionPredicate(
                root = root,
                idAttribute = options.idAttribute,
                id = id,
                versionAttribute = options.versionAttribute,
                expectedVersion = options.expectedVersion,
            ),
        )

        return entityManager.createQuery(update).executeUpdate()
    }

    private fun <E : Any> CriteriaUpdate<E>.setPath(
        root: Root<E>,
        attributePath: String,
        value: Any?,
    ) {
        @Suppress("UNCHECKED_CAST")
        set(root.resolvePath<Any?>(attributePath) as Path<Any?>, value)
    }

    private fun <E : Any, ID : Any> CriteriaBuilder.idAndVersionPredicate(
        root: Root<E>,
        idAttribute: String,
        id: ID,
        versionAttribute: String,
        expectedVersion: Long?,
    ) = if (expectedVersion == null) {
        equal(root.resolvePath<Any?>(idAttribute), id)
    } else {
        and(
            equal(root.resolvePath<Any?>(idAttribute), id),
            equal(root.resolvePath<Long>(versionAttribute), expectedVersion),
        )
    }
}

internal fun <T> Path<*>.resolvePath(attributePath: String): Path<T> {
    val segments = attributePath.trim()
        .split(".")
        .filter { it.isNotBlank() }
    require(segments.isNotEmpty()) {
        "Attribute path must not be blank"
    }

    @Suppress("UNCHECKED_CAST")
    return segments.fold(this) { current, segment ->
        current.get<Any?>(segment)
    } as Path<T>
}
