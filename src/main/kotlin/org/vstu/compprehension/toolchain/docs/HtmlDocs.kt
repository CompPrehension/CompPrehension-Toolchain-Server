package org.vstu.compprehension.toolchain.docs

import org.vstu.compprehension.toolchain.Json
import org.vstu.compprehension.toolchain.rpc.ToolModule

/**
 * Minimal, self-contained (offline) HTML documentation rendered straight from the method registry,
 * plus links to the raw OpenRPC document and the hosted OpenRPC playground.
 */
object HtmlDocs {

    private val prettyWriter = Json.mapper.writerWithDefaultPrettyPrinter()

    fun index(modules: List<ToolModule>, version: String): String = page(
        "CompPrehension Toolchain Server",
        buildString {
            append("<p class=\"lead\">JSON-RPC 2.0 server exposing the CompPrehension toolchains in-process. Version $version.</p>")
            append("<h2>Routes</h2><ul class=\"routes\">")
            for (m in modules) {
                append("<li><code>POST ${esc(m.route)}</code> — ${esc(m.description)} ")
                append("<a href=\"${esc(m.route)}/docs\">docs</a> · ")
                append("<a href=\"${esc(m.route)}/openrpc.json\">openrpc.json</a></li>")
            }
            append("</ul>")
            append("<h2>Health</h2><p><a href=\"/health\">/health</a></p>")
            append(payloadHelp())
        },
    )

    fun modulePage(module: ToolModule, version: String): String = page(
        "${module.title} — Toolchain Server",
        buildString {
            append("<p><a href=\"/\">&larr; all routes</a></p>")
            append("<p class=\"lead\">${esc(module.description)}</p>")
            append("<p><strong>Route:</strong> <code>POST ${esc(module.route)}</code> &nbsp; ")
            append("<a href=\"${esc(module.route)}/openrpc.json\">openrpc.json</a> &nbsp; ")
            append("<a href=\"https://playground.open-rpc.org/?schemaUrl=${esc(module.route)}/openrpc.json\" target=\"_blank\" rel=\"noopener\">open in OpenRPC playground</a></p>")
            for (method in module.methods) {
                append("<section class=\"method\">")
                append("<h3><code>${esc(method.name)}</code></h3>")
                append("<p>${esc(method.description)}</p>")
                if (method.params.isNotEmpty()) {
                    append("<table><thead><tr><th>param</th><th>required</th><th>description</th><th>schema</th></tr></thead><tbody>")
                    for (param in method.params) {
                        append("<tr><td><code>${esc(param.name)}</code></td>")
                        append("<td>${if (param.required) "yes" else "no"}</td>")
                        append("<td>${esc(param.description)}</td>")
                        append("<td><pre>${esc(prettyWriter.writeValueAsString(param.schema))}</pre></td></tr>")
                    }
                    append("</tbody></table>")
                } else {
                    append("<p><em>No parameters.</em></p>")
                }
                append("<details><summary>result schema</summary><pre>${esc(prettyWriter.writeValueAsString(method.result.schema))}</pre></details>")
                append("</section>")
            }
        },
    )

    private fun payloadHelp(): String = """
        <h2>File &amp; directory payloads</h2>
        <p>Send requests as <code>application/json</code> (inline payloads) or <code>multipart/form-data</code>
        (a <code>request</code> field holding the JSON-RPC body, plus file parts referenced by name).</p>
        <p><strong>FileSource</strong>: a JSON string (inline text), or an object
        <code>{"text":"..."}</code> | <code>{"base64":"..."}</code> | <code>{"ref":"&lt;multipart field&gt;"}</code>.</p>
        <p><strong>DirSource</strong> (imitated directory): <code>{"files":{"relative/path":FileSource, ...}}</code>
        or <code>{"ref":"&lt;multipart .zip field&gt;"}</code>. Relevant file types: <code>.loqi</code>,
        <code>.xml</code>, <code>.tpg</code>, and for CSV-dictionary builds <code>.csv</code> + <code>domain.ttl</code>.</p>
    """.trimIndent()

    private fun page(title: String, body: String): String = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${esc(title)}</title>
        <style>
          :root { color-scheme: light dark; }
          body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 2rem auto; max-width: 60rem; padding: 0 1rem; line-height: 1.5; }
          code { background: rgba(127,127,127,.18); padding: .1em .35em; border-radius: 4px; }
          pre { background: rgba(127,127,127,.12); padding: .75rem; border-radius: 6px; overflow:auto; font-size: .85em; }
          table { border-collapse: collapse; width: 100%; margin: .5rem 0 1rem; }
          th, td { border: 1px solid rgba(127,127,127,.35); padding: .4rem .6rem; text-align: left; vertical-align: top; }
          th { background: rgba(127,127,127,.12); }
          .method { border-top: 2px solid rgba(127,127,127,.25); padding-top: .5rem; margin-top: 1.5rem; }
          .lead { font-size: 1.05em; }
          h1 { margin-bottom: .2rem; }
        </style></head><body>
        <h1>${esc(title)}</h1>
        $body
        </body></html>
    """.trimIndent()

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
