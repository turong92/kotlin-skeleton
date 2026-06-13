package dev.sumin.skeleton.api

import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.json.JsonDocument
import dev.sumin.skeleton.json.VersionedJsonDocument
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * JSON contract probes for the executable workbench app.
 * Real services can delete this controller after choosing their JSON conventions.
 */
@Tag(name = "Skeleton JSON")
@RestController
@RequestMapping("/api/v1/skeleton/json")
class SkeletonJsonController(
    private val jsonCodec: JsonCodec,
) {
    @PostMapping(
        "/echo",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Operation(
        summary = "Echo arbitrary JSON document",
        description = "Accepts raw JSON through JsonDocument and returns it in the standard DataResponse envelope.",
        responses = [
            ApiResponse(responseCode = "200", description = "Raw JSON payload wrapped by DataResponse"),
        ],
    )
    fun echo(
        @RequestBody body: JsonDocument,
    ) = Response.ok(body)

    @GetMapping(
        "/versioned",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Operation(
        summary = "Return sample versioned JSON document",
        description = "Shows the standard type/version/payload/metadata envelope for persisted or asynchronous JSON payloads.",
        responses = [
            ApiResponse(responseCode = "200", description = "VersionedJsonDocument wrapped by DataResponse"),
        ],
    )
    fun versioned() =
        Response.ok(
            VersionedJsonDocument(
                type = "skeleton.sample-json",
                version = 1,
                payload = jsonCodec.parse("""{"name":"sample","enabled":true}"""),
                metadata = mapOf("source" to "workbench"),
            ),
        )
}
