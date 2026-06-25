package org.vstu.compprehension.toolchain.files

import com.fasterxml.jackson.databind.JsonNode
import org.vstu.compprehension.toolchain.LocalFilesDiscovery
import org.vstu.compprehension.toolchain.rpc.RpcErrorCodes
import org.vstu.compprehension.toolchain.rpc.RpcException
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories

/**
 * Resolves the "imitated" file and directory payloads carried by an RPC request and materializes
 * them into a real, short-lived temporary directory on disk. Everything created here is deleted by
 * [close], which the dispatcher calls in a `finally` block after each request.
 *
 * Payload shapes accepted (see project README / OpenRPC docs):
 *  - FileSource: a bare JSON string (inline text), or an object with one of
 *      `{ "text": "..." }`, `{ "base64": "..." }`, `{ "ref": "<multipart field name>" }`,
 *      `{ "path": "<absolute local path>" }` (requires LOCAL_FILES_DISCOVERY=true).
 *  - DirSource: `{ "files": { "<relative/path>": <FileSource>, ... } }`,
 *      `{ "ref": "<multipart field name of a .zip archive>" }`,
 *      or `{ "path": "<absolute local directory path>" }` (requires LOCAL_FILES_DISCOVERY=true).
 *
 * @param parts multipart file parts keyed by their form-field name (empty for application/json).
 */
class FileWorkspace(private val parts: Map<String, ByteArray>) : Closeable {

    private val tempRoots = mutableListOf<Path>()

    /** Lazily-created scratch directory used for single materialized files. */
    private val scratch: Path by lazy { newTempDir("file") }

    private fun newTempDir(kind: String): Path {
        val dir = Files.createTempDirectory("compph-rpc-$kind-")
        tempRoots.add(dir)
        return dir
    }

    // ---- FileSource resolution -------------------------------------------------------------

    fun resolveBytes(node: JsonNode?, label: String): ByteArray {
        if (node == null || node.isNull) {
            throw RpcException(RpcErrorCodes.INVALID_PARAMS, "Missing required file payload '$label'")
        }
        if (node.isTextual) {
            return node.asText().toByteArray(StandardCharsets.UTF_8)
        }
        if (!node.isObject) {
            throw payloadError("File payload '$label' must be a string or an object with text/base64/ref/path")
        }
        node.get("text")?.let { return it.asText().toByteArray(StandardCharsets.UTF_8) }
        node.get("base64")?.let {
            return try {
                Base64.getDecoder().decode(it.asText())
            } catch (e: IllegalArgumentException) {
                throw payloadError("File payload '$label' has invalid base64: ${e.message}")
            }
        }
        node.get("ref")?.let { refNode ->
            val ref = refNode.asText()
            return parts[ref] ?: throw payloadError(
                "File payload '$label' references multipart part '$ref' which was not uploaded"
            )
        }
        node.get("path")?.let { pathNode ->
            requireLocalFilesEnabled(label)
            val path = Path.of(pathNode.asText())
            if (!Files.isRegularFile(path)) {
                throw payloadError("Local file does not exist or is not a regular file: $path")
            }
            return Files.readAllBytes(path)
        }
        throw payloadError("File payload '$label' must contain one of: text, base64, ref, path")
    }

    fun resolveText(node: JsonNode?, label: String): String =
        String(resolveBytes(node, label), StandardCharsets.UTF_8)

    /**
     * Returns a [Path] to a materialized file for APIs that need a real path (e.g. XML/TTL builders).
     *
     * When the payload is `{"path":"..."}` and LOCAL_FILES_DISCOVERY is enabled, the local path is
     * returned directly (no copy). Otherwise the bytes are written into a scratch temp directory.
     */
    fun materializeFile(node: JsonNode?, label: String, fallbackName: String): Path {
        if (node != null && !node.isNull && node.isObject) {
            node.get("path")?.let { pathNode ->
                requireLocalFilesEnabled(label)
                val path = Path.of(pathNode.asText())
                if (!Files.isRegularFile(path)) {
                    throw payloadError("Local file does not exist or is not a regular file: $path")
                }
                return path
            }
        }
        val bytes = resolveBytes(node, label)
        val name = (node?.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: fallbackName)
        val safe = sanitizeFileName(name)
        val target = scratch.resolve(safe)
        Files.write(target, bytes)
        return target
    }

    // ---- DirSource resolution --------------------------------------------------------------

    /**
     * Materializes a DirSource into a directory path and returns it.
     *
     * When the payload is `{"path":"..."}` and LOCAL_FILES_DISCOVERY is enabled, the local
     * directory is returned directly (no copy, no cleanup on close). Otherwise a fresh temporary
     * directory is created, populated, and registered for cleanup.
     */
    fun materializeDir(node: JsonNode?, label: String): Path {
        if (node == null || node.isNull) {
            throw RpcException(RpcErrorCodes.INVALID_PARAMS, "Missing required directory payload '$label'")
        }
        if (!node.isObject) {
            throw payloadError("Directory payload '$label' must be an object with 'files', 'ref', or 'path'")
        }

        node.get("path")?.let { pathNode ->
            requireLocalFilesEnabled(label)
            val path = Path.of(pathNode.asText())
            if (!Files.isDirectory(path)) {
                throw payloadError("Local directory does not exist: $path")
            }
            return path
        }

        val dir = newTempDir("dir")

        val filesNode = node.get("files")
        if (filesNode != null && !filesNode.isNull) {
            if (!filesNode.isObject) throw payloadError("'$label.files' must be an object of relativePath -> FileSource")
            val fields = filesNode.fields()
            var count = 0
            while (fields.hasNext()) {
                val (rel, source) = fields.next()
                val rels = "$label.files['$rel']"
                val bytes = resolveBytes(source, rels)
                val target = resolveInside(dir, rel, label)
                target.parent?.createDirectories()
                Files.write(target, bytes)
                count++
            }
            if (count == 0) throw payloadError("Directory payload '$label' is empty")
            return dir
        }

        val ref = node.get("ref")?.asText()
        if (ref != null) {
            val zipBytes = parts[ref] ?: throw payloadError(
                "Directory payload '$label' references multipart part '$ref' which was not uploaded"
            )
            unzipInto(zipBytes, dir, label)
            return dir
        }

        throw payloadError("Directory payload '$label' must contain one of: files, ref, path")
    }

    private fun unzipInto(zipBytes: ByteArray, dir: Path, label: String) {
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    val target = resolveInside(dir, entry.name, label)
                    target.parent?.createDirectories()
                    Files.newOutputStream(target).use { out -> zip.copyTo(out) }
                    count++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            if (count == 0) throw payloadError("Zip payload '$label' contained no files")
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun requireLocalFilesEnabled(label: String) {
        if (!LocalFilesDiscovery.isEnabled()) {
            throw RpcException(
                RpcErrorCodes.LOCAL_FILES_DISABLED,
                "Payload '$label' uses the 'path' variant, but LOCAL_FILES_DISCOVERY is not enabled " +
                "on this server. Set LOCAL_FILES_DISCOVERY=true in the server .env to allow local " +
                "file-path references (local deployments only — do not enable on a network-exposed server)."
            )
        }
    }

    private fun resolveInside(base: Path, relative: String, label: String): Path {
        val normalizedRel = relative.replace('\\', '/').trim('/')
        if (normalizedRel.isEmpty()) throw payloadError("Empty relative path in '$label'")
        val resolved = base.resolve(normalizedRel).normalize()
        if (!resolved.startsWith(base.normalize())) {
            throw payloadError("Path traversal detected in '$label': '$relative'")
        }
        return resolved
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace('\\', '/').substringAfterLast('/').trim()
        return cleaned.ifEmpty { "input" }
    }

    private fun payloadError(message: String) = RpcException(RpcErrorCodes.PAYLOAD_ERROR, message)

    override fun close() {
        for (root in tempRoots.asReversed()) {
            runCatching { deleteRecursively(root) }
        }
        tempRoots.clear()
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                runCatching { Files.deleteIfExists(p) }
            }
        }
    }
}
