package dev.sumin.skeleton.json

import kotlin.reflect.KClass

interface JsonPayloadDefinition<T : Any> {
    val type: String
    val currentVersion: Int
    val payloadClass: KClass<T>
}

interface JsonPayloadMigrator {
    val type: String
    val fromVersion: Int
    val toVersion: Int

    fun migrate(payload: JsonDocument): JsonDocument
}

class JsonPayloadRegistry(
    private val codec: JsonCodec,
    definitions: List<JsonPayloadDefinition<*>>,
    migrators: List<JsonPayloadMigrator>,
) {
    private val definitionsByType = definitions.associateBy { definition ->
        require(definition.type.isNotBlank()) { "JSON payload type must not be blank." }
        require(definition.currentVersion > 0) { "JSON payload current version must be positive." }
        definition.type
    }
    private val migratorsByStep = migrators.associateBy { migrator ->
        require(migrator.type.isNotBlank()) { "JSON payload migrator type must not be blank." }
        require(migrator.fromVersion > 0) { "JSON payload migrator fromVersion must be positive." }
        require(migrator.toVersion == migrator.fromVersion + 1) {
            "JSON payload migrators must move exactly one version at a time."
        }
        MigratorKey(migrator.type, migrator.fromVersion)
    }

    fun <T : Any> readLatest(
        document: VersionedJsonDocument,
        targetClass: KClass<T>,
    ): T {
        val latest = migrateToLatest(document)
        return codec.fromDocument(latest.payload, targetClass)
    }

    fun <T : Any> readLatest(
        document: VersionedJsonDocument,
        definition: JsonPayloadDefinition<T>,
    ): T {
        require(document.type == definition.type) {
            "JSON payload type '${document.type}' does not match definition '${definition.type}'."
        }
        return readLatest(document, definition.payloadClass)
    }

    fun <T : Any> writeLatest(
        type: String,
        payload: T,
        metadata: Map<String, String> = emptyMap(),
    ): VersionedJsonDocument {
        val definition = definition(type)
        require(definition.payloadClass.java.isAssignableFrom(payload::class.java)) {
            "Payload class '${payload::class.qualifiedName}' is not assignable to '${definition.payloadClass.qualifiedName}'."
        }
        return VersionedJsonDocument(
            type = type,
            version = definition.currentVersion,
            payload = codec.toDocument(payload),
            metadata = metadata,
        )
    }

    fun <T : Any> writeLatest(
        definition: JsonPayloadDefinition<T>,
        payload: T,
        metadata: Map<String, String> = emptyMap(),
    ): VersionedJsonDocument =
        writeLatest(
            type = definition.type,
            payload = payload,
            metadata = metadata,
        )

    fun migrateToLatest(document: VersionedJsonDocument): VersionedJsonDocument {
        val definition = definition(document.type)
        require(document.version <= definition.currentVersion) {
            "JSON payload '${document.type}' version ${document.version} is newer than current ${definition.currentVersion}."
        }

        var current = document
        while (current.version < definition.currentVersion) {
            val migrator = migratorsByStep[MigratorKey(current.type, current.version)]
                ?: error("Missing JSON payload migrator for '${current.type}' v${current.version} to v${current.version + 1}.")
            current = current.copy(
                version = migrator.toVersion,
                payload = migrator.migrate(current.payload),
            )
        }
        return current
    }

    private fun definition(type: String): JsonPayloadDefinition<*> =
        definitionsByType[type] ?: error("Unknown JSON payload type '$type'.")

    private data class MigratorKey(
        val type: String,
        val fromVersion: Int,
    )
}
