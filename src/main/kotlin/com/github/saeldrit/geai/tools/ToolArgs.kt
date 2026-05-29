package com.github.saeldrit.geai.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Thrown when a tool's required argument is missing or malformed; surfaced back to the model. */
class ToolArgException(message: String) : Exception(message)

/**
 * Typed, null-safe accessor over a tool's raw JSON arguments. Malformed input degrades to an
 * empty object so a tool can report a precise [ToolArgException] instead of crashing the loop.
 */
class ToolArgs private constructor(private val obj: JsonObject) {

    fun stringOrNull(key: String): String? =
        obj.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    fun string(key: String): String =
        stringOrNull(key) ?: throw ToolArgException("Missing required string argument '$key'")

    fun intOrNull(key: String): Int? =
        obj.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt

    fun int(key: String, default: Int): Int = intOrNull(key) ?: default

    fun boolean(key: String, default: Boolean): Boolean =
        obj.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: default

    fun stringList(key: String): List<String> =
        obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
            ?: emptyList()

    companion object {
        fun parse(json: String): ToolArgs {
            val parsed = runCatching { JsonParser.parseString(json.ifBlank { "{}" }) }.getOrNull()
            val obj = parsed?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            return ToolArgs(obj)
        }
    }
}
