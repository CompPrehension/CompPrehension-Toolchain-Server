package org.vstu.compprehension.toolchain

import com.fasterxml.jackson.databind.node.NullNode
import io.javalin.Javalin
import io.javalin.http.Context
import org.vstu.compprehension.toolchain.docs.HtmlDocs
import org.vstu.compprehension.toolchain.docs.OpenRpc
import org.vstu.compprehension.toolchain.rpc.Dispatcher
import org.vstu.compprehension.toolchain.rpc.RpcError
import org.vstu.compprehension.toolchain.rpc.RpcErrorCodes
import org.vstu.compprehension.toolchain.rpc.RpcResponse
import org.vstu.compprehension.toolchain.rpc.ToolModule
import org.vstu.compprehension.toolchain.tools.DomainService
import org.vstu.compprehension.toolchain.tools.MeaningTreeService
import org.vstu.compprehension.toolchain.tools.ReasonerService

const val SERVER_VERSION = "0.1.0"

private val MODULES: List<ToolModule> = listOf(
    DomainService.module(),
    ReasonerService.module(),
    MeaningTreeService.module(),
)

fun main(args: Array<String>) {
    val host = Env["HOST"] ?: "0.0.0.0"
    val port = (args.firstOrNull() ?: Env["PORT"])?.toIntOrNull() ?: 8080
    Auth.secret = Env["ACCESS_SECRET"]?.takeIf { it.isNotBlank() }

    val app = Javalin.create { config ->
        config.showJavalinBanner = false
        config.http.maxRequestSize = 64L * 1024 * 1024 // 64 MiB inline bodies
    }

    app.get("/") { ctx -> ctx.html(HtmlDocs.index(MODULES, SERVER_VERSION)) }
    app.get("/health") { ctx -> ctx.contentType("application/json").result(Json.writeString(mapOf("status" to "ok", "version" to SERVER_VERSION))) }

    for (module in MODULES) {
        app.post(module.route) { ctx -> handleRpc(module, ctx) }
        app.get("${module.route}/openrpc.json") { ctx ->
            ctx.contentType("application/json").result(Json.writeString(OpenRpc.document(module, SERVER_VERSION, module.route)))
        }
        app.get("${module.route}/docs") { ctx -> ctx.html(HtmlDocs.modulePage(module, SERVER_VERSION)) }
    }

    app.start(host, port)

    println("CompPrehension Toolchain Server $SERVER_VERSION listening on http://$host:$port")
    println("Docs:  http://$host:$port/")
    println(if (Auth.isConfigured()) "Access secret: ENABLED (send 'X-Access-Secret' header)" else "Access secret: disabled (open access)")
    MODULES.forEach { println("  POST ${it.route}   (docs: ${it.route}/docs)") }
}

private fun handleRpc(module: ToolModule, ctx: Context) {
    if (!Auth.authorized(ctx)) {
        ctx.status(403).contentType("application/json")
            .result(Json.writeString(mapOf("error" to "Forbidden: missing or invalid access secret")))
        return
    }
    val parts = LinkedHashMap<String, ByteArray>()
    val bodyText: String
    try {
        if (ctx.isMultipartFormData()) {
            ctx.uploadedFileMap().forEach { (field, files) ->
                files.firstOrNull()?.let { file ->
                    val bytes = file.content().readBytes()
                    parts[field] = bytes
                    val filename = file.filename()
                    if (!filename.isNullOrBlank() && !parts.containsKey(filename)) parts[filename] = bytes
                }
            }
            bodyText = ctx.formParam("request")
                ?: parts["request"]?.toString(Charsets.UTF_8)
                ?: return writeError(ctx, RpcErrorCodes.INVALID_REQUEST,
                    "multipart/form-data request must include a 'request' field with the JSON-RPC body")
        } else {
            bodyText = ctx.body()
        }
    } catch (e: Exception) {
        return writeError(ctx, RpcErrorCodes.INTERNAL_ERROR, "Failed to read request: ${e.message}")
    }

    val body = try {
        Json.parse(bodyText)
    } catch (e: Exception) {
        return writeError(ctx, RpcErrorCodes.PARSE_ERROR, "Invalid JSON: ${e.message}")
    }

    val result = Dispatcher.handle(module, body, parts)
    if (result == null) {
        ctx.status(204)
    } else {
        ctx.contentType("application/json").result(Json.writeString(result))
    }
}

private fun writeError(ctx: Context, code: Int, message: String) {
    val response = RpcResponse.failure(NullNode.instance, RpcError(code, message))
    ctx.status(200).contentType("application/json").result(Json.writeString(response))
}
