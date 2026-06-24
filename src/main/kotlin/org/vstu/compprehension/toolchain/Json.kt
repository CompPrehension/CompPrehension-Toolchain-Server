package org.vstu.compprehension.toolchain

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Shared JSON facilities. A single [ObjectMapper] is reused across all requests (Jackson's mapper
 * is thread-safe once configured).
 */
object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun writeString(value: Any?): String = mapper.writeValueAsString(value)

    fun parse(text: String): JsonNode = mapper.readTree(text)
}

/** Returns the textual value of a field, or null if it is missing or JSON null. */
fun JsonNode?.textOrNull(field: String): String? {
    val node = this?.get(field) ?: return null
    if (node.isNull) return null
    return node.asText()
}

/** Returns a field node, treating JSON null and absence identically as null. */
fun JsonNode?.child(field: String): JsonNode? {
    val node = this?.get(field) ?: return null
    if (node is NullNode) return null
    return node
}

fun JsonNode?.boolOr(field: String, default: Boolean): Boolean {
    val node = this?.get(field) ?: return default
    if (node.isNull) return default
    return node.asBoolean(default)
}

fun JsonNode?.intOrNull(field: String): Int? {
    val node = this?.get(field) ?: return null
    if (node.isNull) return null
    return node.asInt()
}

fun JsonNode?.longOrNull(field: String): Long? {
    val node = this?.get(field) ?: return null
    if (node.isNull) return null
    return node.asLong()
}
