package org.vstu.compprehension.toolchain.rpc

import com.fasterxml.jackson.databind.JsonNode
import org.vstu.compprehension.toolchain.files.FileWorkspace

/** The context passed to a method handler: parsed params plus the per-request file workspace. */
class RpcCall(
    val params: JsonNode?,
    val workspace: FileWorkspace,
)

/** Documentation for a single method parameter, also used to render the OpenRPC contentDescriptor. */
data class ParamDoc(
    val name: String,
    val description: String,
    val required: Boolean,
    val schema: Map<String, Any?>,
)

/** Documentation for a method result. */
data class ResultDoc(
    val name: String,
    val description: String,
    val schema: Map<String, Any?>,
)

/** A single JSON-RPC method: metadata for docs + the executable handler. */
class RpcMethod(
    val name: String,
    val summary: String,
    val description: String,
    val params: List<ParamDoc>,
    val result: ResultDoc,
    val handler: (RpcCall) -> Any?,
)

/** A tool module bound to one HTTP route, exposing a set of related JSON-RPC methods. */
class ToolModule(
    val route: String,
    val title: String,
    val description: String,
    val methods: List<RpcMethod>,
) {
    val methodsByName: Map<String, RpcMethod> = methods.associateBy { it.name }
}

// ---- Tiny JSON-Schema helpers used by the service definitions and OpenRPC generator ----------

object Schemas {
    fun string(description: String? = null): Map<String, Any?> =
        buildMap { put("type", "string"); description?.let { put("description", it) } }

    fun boolean(default: Boolean? = null): Map<String, Any?> =
        buildMap { put("type", "boolean"); default?.let { put("default", it) } }

    fun integer(): Map<String, Any?> = mapOf("type" to "integer")

    fun enumOf(vararg values: String): Map<String, Any?> =
        mapOf("type" to "string", "enum" to values.toList())

    fun obj(description: String? = null): Map<String, Any?> =
        buildMap { put("type", "object"); description?.let { put("description", it) } }

    /** Schema describing the FileSource union accepted by [FileWorkspace]. */
    fun fileSource(): Map<String, Any?> = mapOf(
        "title" to "FileSource",
        "description" to "Inline string (text), or an object: {text} | {base64} | {ref:<multipart field>}",
        "oneOf" to listOf(
            mapOf("type" to "string"),
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf("type" to "string"),
                    "base64" to mapOf("type" to "string"),
                    "ref" to mapOf("type" to "string", "description" to "Multipart form-field name of the uploaded file"),
                    "name" to mapOf("type" to "string", "description" to "Optional file name hint")
                )
            )
        )
    )

    /** Schema describing the DirSource union accepted by [FileWorkspace]. */
    fun dirSource(): Map<String, Any?> = mapOf(
        "title" to "DirSource",
        "description" to "An imitated directory: {files:{relpath:FileSource}} or {ref:<multipart zip field>}",
        "type" to "object",
        "properties" to mapOf(
            "files" to mapOf(
                "type" to "object",
                "description" to "Map of relative path -> FileSource",
                "additionalProperties" to fileSource()
            ),
            "ref" to mapOf("type" to "string", "description" to "Multipart form-field name of an uploaded .zip archive")
        )
    )
}
