package org.vstu.compprehension.toolchain.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import org.vstu.compprehension.toolchain.Json
import org.vstu.compprehension.toolchain.textOrNull
import org.vstu.compprehension.toolchain.rpc.Dispatcher
import org.vstu.compprehension.toolchain.rpc.RpcError
import org.vstu.compprehension.toolchain.rpc.RpcErrorCodes
import org.vstu.compprehension.toolchain.rpc.RpcException
import org.vstu.compprehension.toolchain.rpc.RpcMethod
import org.vstu.compprehension.toolchain.rpc.RpcResponse
import org.vstu.compprehension.toolchain.rpc.ToolModule

/**
 * A thin Model Context Protocol (https://modelcontextprotocol.io) adapter over the existing
 * JSON-RPC tool registry. MCP is itself JSON-RPC 2.0, so this bridge only has to:
 *
 *  - flatten every module's methods into one MCP tool namespace (`<moduleId>__<method>`),
 *  - answer the handful of MCP protocol methods (`initialize`, `tools/list`, `tools/call`, `ping`),
 *  - reuse [Dispatcher.invoke] to actually execute a tool.
 *
 * The tool list is derived from [modules] once at construction, so any method added to a service —
 * or any new module added to the registry — shows up over MCP automatically, with no changes here.
 *
 * Transport note: this speaks the request/response half of MCP "Streamable HTTP" (a single JSON
 * response per request). Multipart file `ref`s are unavailable over MCP; use the `text`/`base64`/
 * `path` FileSource variants instead.
 */
class McpBridge(
    modules: List<ToolModule>,
    private val serverName: String,
    private val version: String,
) {
    /** Insertion-ordered map of MCP tool name -> backing method. Order drives `tools/list`. */
    private val tools: Map<String, RpcMethod> = buildMap {
        for (module in modules) {
            val moduleId = module.route.removePrefix("/rpc/")
            for (method in module.methods) {
                val name = "${moduleId}__${method.name}"
                require(name.matches(TOOL_NAME_PATTERN)) { "MCP tool name not client-safe: '$name'" }
                put(name, method)
            }
        }
    }

    private val prettyWriter = Json.mapper.writerWithDefaultPrettyPrinter()

    /**
     * Handles one MCP JSON-RPC message. Returns the object to serialize back, or `null` when the
     * message was a notification (no response body warranted).
     */
    fun handle(body: JsonNode): Any? {
        if (!body.isObject) {
            return RpcResponse.failure(
                NullNode.instance,
                RpcError(RpcErrorCodes.INVALID_REQUEST, "MCP request must be a single JSON-RPC object"),
            )
        }
        val isNotification = !body.has("id")
        val id: JsonNode = if (body.has("id")) body.get("id") else NullNode.instance
        val method = body.get("method")?.takeIf { it.isTextual }?.asText()
            ?: return if (isNotification) null
            else RpcResponse.failure(id, RpcError(RpcErrorCodes.INVALID_REQUEST, "Missing or non-string 'method'"))
        val params = body.get("params")

        return try {
            when (method) {
                "initialize" -> reply(id, isNotification, initialize(params))
                "ping" -> reply(id, isNotification, emptyMap<String, Any?>())
                "tools/list" -> reply(id, isNotification, toolsList())
                "tools/call" -> reply(id, isNotification, toolsCall(params))
                else ->
                    // Unknown notifications (e.g. notifications/initialized, notifications/cancelled)
                    // are silently accepted; unknown requests get a proper method-not-found error.
                    if (isNotification) null
                    else RpcResponse.failure(id, RpcError(RpcErrorCodes.METHOD_NOT_FOUND, "Unknown MCP method '$method'"))
            }
        } catch (e: RpcException) {
            if (isNotification) null else RpcResponse.failure(id, e.error)
        }
    }

    private fun reply(id: JsonNode, isNotification: Boolean, result: Any?): Any? =
        if (isNotification) null else RpcResponse.success(id, result)

    // ---- MCP methods -----------------------------------------------------------------------------

    private fun initialize(params: JsonNode?): Map<String, Any?> = mapOf(
        // Echo the client's requested protocol version when present; otherwise offer our default.
        "protocolVersion" to (params.textOrNull("protocolVersion") ?: DEFAULT_PROTOCOL_VERSION),
        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
        "serverInfo" to mapOf("name" to serverName, "version" to version),
        "instructions" to INSTRUCTIONS,
    )

    private fun toolsList(): Map<String, Any?> =
        mapOf("tools" to tools.map { (name, method) -> toolDescriptor(name, method) })

    private fun toolDescriptor(name: String, method: RpcMethod): Map<String, Any?> = buildMap {
        put("name", name)
        put("description", description(method))
        put("inputSchema", inputSchema(method))
        // Advertise an outputSchema only when the result is an object (MCP requires object schemas here).
        if (method.result.schema["type"] == "object") put("outputSchema", method.result.schema)
    }

    private fun toolsCall(params: JsonNode?): Map<String, Any?> {
        val name = params.textOrNull("name")
            ?: throw RpcException(RpcErrorCodes.INVALID_PARAMS, "tools/call requires a 'name'")
        val method = tools[name]
            ?: throw RpcException(RpcErrorCodes.METHOD_NOT_FOUND, "Unknown tool '$name'. Known: ${tools.keys.sorted()}")
        val arguments = params?.get("arguments")
        return try {
            successResult(Dispatcher.invoke(method, arguments, emptyMap()))
        } catch (e: RpcException) {
            // Per MCP convention, tool execution failures are reported in-band (isError) rather than
            // as a JSON-RPC protocol error, so the agent can read and recover from the message.
            errorResult(e.error)
        }
    }

    // ---- Result shaping --------------------------------------------------------------------------

    private fun successResult(result: Any?): Map<String, Any?> = buildMap {
        put("content", listOf(mapOf("type" to "text", "text" to prettyWriter.writeValueAsString(result))))
        // structuredContent must be an object; only attach it when the result actually is one.
        val tree = Json.mapper.valueToTree<JsonNode>(result)
        if (tree != null && tree.isObject) put("structuredContent", result)
    }

    private fun errorResult(error: RpcError): Map<String, Any?> {
        val text = buildString {
            append(error.message)
            (error.data as? Map<*, *>)?.get("rootCauseMessage")?.let { if (it != null) append("\n").append(it) }
        }
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "isError" to true,
        )
    }

    private fun description(method: RpcMethod): String =
        if (method.summary.isNotBlank() && !method.description.startsWith(method.summary)) {
            "${method.summary}\n\n${method.description}"
        } else {
            method.description
        }

    private fun inputSchema(method: RpcMethod): Map<String, Any?> {
        val properties = LinkedHashMap<String, Any?>()
        val required = ArrayList<String>()
        for (param in method.params) {
            properties[param.name] = param.schema
            if (param.required) required.add(param.name)
        }
        return buildMap {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) put("required", required)
        }
    }

    companion object {
        /** Latest MCP protocol revision we implement; used when the client doesn't request one. */
        const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"

        /** MCP clients commonly restrict tool names to this character set. */
        private val TOOL_NAME_PATTERN = Regex("^[A-Za-z0-9_-]+\$")

        private val INSTRUCTIONS = """
            CompPrehension toolchain exposed over MCP. Tools are named <module>__<method>, e.g.
            'meaning-tree__list-langs' or 'domain__validateDsm'; each tool's inputSchema documents its
            arguments.

            File/directory arguments use the FileSource/DirSource convention. Over MCP a FileSource is
            an inline string (file text) or an object {"text":...} | {"base64":...} | {"path":...}; a
            DirSource is {"files":{"relative/path":FileSource,...}} or {"path":...}. The {"ref":...}
            (multipart upload) variant is NOT available over MCP. The {"path":...} variant references a
            local filesystem path and requires the server to run with LOCAL_FILES_DISCOVERY=true.
        """.trimIndent()
    }
}
