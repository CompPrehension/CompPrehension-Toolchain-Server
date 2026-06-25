package org.vstu.compprehension.toolchain

/**
 * Controls whether the server may read files and directories directly from the local filesystem
 * when a client sends a `{"path": "..."}` FileSource or DirSource payload.
 *
 * Enabled by setting `LOCAL_FILES_DISCOVERY=true` in the server `.env`. Intended for local
 * development where serializing large model directories into JSON payloads is slow.
 *
 * WARNING: must NOT be enabled when the server is exposed on a network — any authenticated caller
 * would be able to read arbitrary paths accessible to the server process.
 */
object LocalFilesDiscovery {
    @Volatile
    var enabled: Boolean = false

    fun isEnabled(): Boolean = enabled
}
