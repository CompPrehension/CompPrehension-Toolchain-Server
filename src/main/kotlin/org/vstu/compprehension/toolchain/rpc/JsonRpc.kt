package org.vstu.compprehension.toolchain.rpc

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.databind.JsonNode

/** Standard JSON-RPC 2.0 error codes plus a server-defined range for tool failures. */
object RpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** Tool/handler raised an exception while processing valid params. */
    const val TOOL_EXECUTION_ERROR = -32000

    /** A referenced file/directory payload could not be resolved or materialized. */
    const val PAYLOAD_ERROR = -32001

    /** Client sent a {"path":"..."} FileSource/DirSource but LOCAL_FILES_DISCOVERY is disabled. */
    const val LOCAL_FILES_DISABLED = -32002
}

/** A JSON-RPC error object. */
data class RpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
)

/** Thrown by handlers (or core) to short-circuit into a JSON-RPC error response. */
class RpcException(val error: RpcError) : RuntimeException(error.message) {
    constructor(code: Int, message: String, data: Any? = null) : this(RpcError(code, message, data))
}

/** Convenience for the most common handler-side failure: bad parameters. */
fun invalidParams(message: String, data: Any? = null): Nothing =
    throw RpcException(RpcErrorCodes.INVALID_PARAMS, message, data)

/** A parsed JSON-RPC request object. */
data class RpcRequest(
    val jsonrpc: String?,
    val method: String?,
    val params: JsonNode?,
    val id: JsonNode?,
    val hasId: Boolean,
)

/** A JSON-RPC response object (serialized directly by Jackson). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder("jsonrpc", "id", "result", "error")
class RpcResponse private constructor(
    val id: JsonNode?,
    val result: Any?,
    val error: RpcError?,
) {
    val jsonrpc: String = "2.0"

    companion object {
        fun success(id: JsonNode?, result: Any?) = RpcResponse(id, result, null)
        fun failure(id: JsonNode?, error: RpcError) = RpcResponse(id, null, error)
    }
}
