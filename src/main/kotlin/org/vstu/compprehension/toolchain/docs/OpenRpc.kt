package org.vstu.compprehension.toolchain.docs

import org.vstu.compprehension.toolchain.rpc.ToolModule

/**
 * Generates an OpenRPC (https://open-rpc.org) document for a [ToolModule] — the JSON-RPC analogue
 * of an OpenAPI/Swagger spec. The document is built straight from the method registry, so it always
 * matches what the server actually serves.
 */
object OpenRpc {

    const val OPENRPC_VERSION = "1.3.2"

    fun document(module: ToolModule, version: String, serverUrl: String): Map<String, Any?> = mapOf(
        "openrpc" to OPENRPC_VERSION,
        "info" to mapOf(
            "title" to "CompPrehension Toolchain Server — ${module.title}",
            "version" to version,
            "description" to module.description,
        ),
        "servers" to listOf(mapOf("name" to module.title, "url" to serverUrl)),
        "methods" to module.methods.map { method ->
            mapOf(
                "name" to method.name,
                "summary" to method.summary,
                "description" to method.description,
                "paramStructure" to "by-name",
                "params" to method.params.map { param ->
                    mapOf(
                        "name" to param.name,
                        "description" to param.description,
                        "required" to param.required,
                        "schema" to param.schema,
                    )
                },
                "result" to mapOf(
                    "name" to method.result.name,
                    "description" to method.result.description,
                    "schema" to method.result.schema,
                ),
            )
        },
    )
}
