package org.vstu.compprehension.toolchain.rpc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import org.vstu.compprehension.toolchain.files.FileWorkspace
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Executes JSON-RPC 2.0 requests (single object or batch array) against a [ToolModule].
 * Returns the value to serialize back: an [RpcResponse], a list of them, or `null` when the
 * request consisted solely of notifications (and therefore warrants no HTTP body).
 */
object Dispatcher {

    fun handle(module: ToolModule, body: JsonNode, parts: Map<String, ByteArray>): Any? {
        if (body.isArray) {
            if (body.isEmpty) {
                return RpcResponse.failure(NullNode.instance, RpcError(RpcErrorCodes.INVALID_REQUEST, "Empty batch"))
            }
            val responses = body.mapNotNull { handleSingle(module, it, parts) }
            return responses.ifEmpty { null }
        }
        return handleSingle(module, body, parts)
    }

    private fun handleSingle(module: ToolModule, node: JsonNode, parts: Map<String, ByteArray>): RpcResponse? {
        val isNotification = node.isObject && !node.has("id")
        val idNode: JsonNode = if (node.isObject && node.has("id")) node.get("id") else NullNode.instance
        try {
            if (!node.isObject) {
                throw RpcException(RpcErrorCodes.INVALID_REQUEST, "Request must be a JSON object")
            }
            val versionNode = node.get("jsonrpc")
            if (versionNode != null && versionNode.asText() != "2.0") {
                throw RpcException(RpcErrorCodes.INVALID_REQUEST, "Unsupported jsonrpc version: ${versionNode.asText()}")
            }
            val method = node.get("method")?.takeIf { it.isTextual }?.asText()
                ?: throw RpcException(RpcErrorCodes.INVALID_REQUEST, "Missing or non-string 'method'")

            val rpcMethod = module.methodsByName[method]
                ?: throw RpcException(
                    RpcErrorCodes.METHOD_NOT_FOUND,
                    "Unknown method '$method' on route '${module.route}'. Known: ${module.methodsByName.keys.sorted()}"
                )

            val result = FileWorkspace(parts).use { ws ->
                rpcMethod.handler(RpcCall(node.get("params"), ws))
            }
            return if (isNotification) null else RpcResponse.success(idNode, result)
        } catch (e: RpcException) {
            return if (isNotification) null else RpcResponse.failure(idNode, e.error)
        } catch (e: Exception) {
            return if (isNotification) null else RpcResponse.failure(idNode, toolError(e))
        }
    }

    private fun toolError(e: Exception): RpcError {
        val cause = rootCause(e)
        return RpcError(
            code = RpcErrorCodes.TOOL_EXECUTION_ERROR,
            message = e.message ?: cause.javaClass.simpleName,
            data = mapOf(
                "exceptionName" to e.javaClass.name,
                "rootCause" to cause.javaClass.name,
                "rootCauseMessage" to cause.message,
                "stackTrace" to shortStackTrace(e),
            ),
        )
    }

    private fun rootCause(e: Throwable): Throwable {
        var current = e
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun shortStackTrace(e: Throwable): String {
        val writer = StringWriter()
        e.printStackTrace(PrintWriter(writer))
        return writer.toString().lineSequence().take(25).joinToString("\n")
    }
}
