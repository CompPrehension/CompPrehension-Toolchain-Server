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

    fun number(): Map<String, Any?> = mapOf("type" to "number")

    fun any(): Map<String, Any?> = emptyMap()

    fun nullable(schema: Map<String, Any?>): Map<String, Any?> =
        mapOf("oneOf" to listOf(schema, mapOf("type" to "null")))

    fun enumOf(vararg values: String): Map<String, Any?> =
        mapOf("type" to "string", "enum" to values.toList())

    fun obj(description: String? = null): Map<String, Any?> =
        buildMap { put("type", "object"); description?.let { put("description", it) } }

    fun array(items: Map<String, Any?>, description: String? = null): Map<String, Any?> =
        buildMap { put("type", "array"); put("items", items); description?.let { put("description", it) } }

    fun objectSchema(
        properties: Map<String, Map<String, Any?>>,
        required: Collection<String> = properties.keys,
        description: String? = null,
    ): Map<String, Any?> = buildMap {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) put("required", required.toList())
        description?.let { put("description", it) }
    }

    /** Schema describing the FileSource union accepted by [FileWorkspace]. */
    fun fileSource(): Map<String, Any?> = fileSource(includeMultipartRef = true)

    /** Schema describing the project source-context forms accepted by meaning_tree. */
    fun projectSource(): Map<String, Any?> = mapOf(
        "oneOf" to listOf(
            string("'<root><platform path separator><currentFileRelPath>'"),
            objectSchema(
                properties = mapOf(
                    "root" to string("Project root path."),
                    "currentFile" to string("Current file path relative to the project root."),
                ),
            ),
        ),
    )

    private fun fileSource(includeMultipartRef: Boolean): Map<String, Any?> = mapOf(
        "title" to "FileSource",
        "description" to if (includeMultipartRef) {
            "Inline string (text), or an object: {text} | {base64} | {ref:<multipart field>} | {path:<local path>}. " +
                "The 'path' variant requires LOCAL_FILES_DISCOVERY=true on the server."
        } else {
            "Inline string (text), or an object: {text} | {base64} | {path:<local path>}. " +
                "The 'path' variant requires LOCAL_FILES_DISCOVERY=true on the server."
        },
        "oneOf" to listOf(
            mapOf("type" to "string"),
            mapOf(
                "type" to "object",
                "properties" to buildMap {
                    put("text", mapOf("type" to "string"))
                    put("base64", mapOf("type" to "string"))
                    if (includeMultipartRef) {
                        put("ref", mapOf("type" to "string", "description" to "Multipart form-field name of the uploaded file"))
                    }
                    put("path", mapOf("type" to "string", "description" to "Absolute local filesystem path (requires LOCAL_FILES_DISCOVERY=true)"))
                    put("name", mapOf("type" to "string", "description" to "Optional file name hint"))
                },
            )
        )
    )

    /** Schema describing the DirSource union accepted by [FileWorkspace]. */
    fun dirSource(): Map<String, Any?> = dirSource(includeMultipartRef = true)

    private fun dirSource(includeMultipartRef: Boolean): Map<String, Any?> = mapOf(
        "title" to "DirSource",
        "description" to if (includeMultipartRef) {
            "An imitated directory: {files:{relpath:FileSource}}, {ref:<multipart zip field>}, " +
                "or {path:<local dir path>} (requires LOCAL_FILES_DISCOVERY=true on the server)."
        } else {
            "An imitated directory: {files:{relpath:FileSource}} or {path:<local dir path>} " +
                "(requires LOCAL_FILES_DISCOVERY=true on the server)."
        },
        "type" to "object",
        "properties" to buildMap {
            put("files", mapOf(
                "type" to "object",
                "description" to "Map of relative path -> FileSource",
                "additionalProperties" to fileSource(includeMultipartRef),
            ))
            if (includeMultipartRef) {
                put("ref", mapOf("type" to "string", "description" to "Multipart form-field name of an uploaded .zip archive"))
            }
            put("path", mapOf("type" to "string", "description" to "Absolute local filesystem path to an existing directory (requires LOCAL_FILES_DISCOVERY=true)"))
        },
    )

    /** Removes transport variants that cannot be carried by an MCP JSON request. */
    fun forMcp(schema: Map<String, Any?>): Map<String, Any?> = when (schema["title"]) {
        "FileSource" -> fileSource(includeMultipartRef = false)
        "DirSource" -> dirSource(includeMultipartRef = false)
        else -> schema
    }
}
