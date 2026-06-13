package dev.sumin.skeleton.persistence.jpa

import jakarta.persistence.EntityGraph
import jakarta.persistence.EntityManager
import jakarta.persistence.Subgraph

class JpaFetchGraphHints(
    private val entityManager: EntityManager,
) {
    fun <E : Any> fetchGraphHints(
        entityClass: Class<E>,
        vararg attributePaths: String,
    ): Map<String, Any> =
        mapOf(FETCH_GRAPH_HINT to fetchGraph(entityClass, *attributePaths))

    fun <E : Any> fetchGraph(
        entityClass: Class<E>,
        vararg attributePaths: String,
    ): EntityGraph<E> {
        val graph = entityManager.createEntityGraph(entityClass)
        attributePaths
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { attributePath -> graph.addAttributePath(attributePath) }
        return graph
    }

    private fun EntityGraph<*>.addAttributePath(attributePath: String) {
        val segments = attributePath.split(".").filter { it.isNotBlank() }
        require(segments.isNotEmpty()) {
            "Attribute path must not be blank"
        }
        if (segments.size == 1) {
            addAttributeNodes(segments.single())
            return
        }

        var subgraph: Subgraph<*> = addSubgraph<Any>(segments.first())
        segments.drop(1).dropLast(1).forEach { segment ->
            subgraph = subgraph.addSubgraph<Any>(segment)
        }
        subgraph.addAttributeNodes(segments.last())
    }

    companion object {
        const val FETCH_GRAPH_HINT: String = "jakarta.persistence.fetchgraph"
    }
}
