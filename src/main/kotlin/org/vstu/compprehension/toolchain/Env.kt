package org.vstu.compprehension.toolchain

import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal configuration source. Reads a `.env` file (if present) once at startup and exposes values,
 * with real process environment variables taking precedence over `.env` entries.
 *
 * The `.env` location defaults to `<working dir>/.env` and can be overridden with the `DOTENV_PATH`
 * environment variable. Supported syntax: `KEY=VALUE` lines, `#` comments, blank lines, an optional
 * leading `export `, and optional surrounding single/double quotes around the value.
 */
object Env {

    private val dotenv: Map<String, String> = load()

    /** Real environment variables win over `.env`; returns null if neither defines [key]. */
    operator fun get(key: String): String? = System.getenv(key) ?: dotenv[key]

    private fun load(): Map<String, String> {
        val path = resolvePath() ?: return emptyMap()
        if (!Files.isRegularFile(path)) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (raw in Files.readAllLines(path)) {
            val line = raw.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = unquote(line.substring(eq + 1).trim())
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    private fun resolvePath(): Path? {
        System.getenv("DOTENV_PATH")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        return Path.of(System.getProperty("user.dir"), ".env")
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
        ) {
            return value.substring(1, value.length - 1)
        }
        return value
    }
}
