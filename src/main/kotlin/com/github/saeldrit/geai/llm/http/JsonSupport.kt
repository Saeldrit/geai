package com.github.saeldrit.geai.llm.http

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

internal object JsonSupport {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseElement(raw: String): JsonElement =
        JsonParser.parseReader(JsonReader(StringReader(raw)).apply { isLenient = true })

    fun parseObject(raw: String): JsonObject = parseElement(raw).asJsonObject

    /**
     * A human-readable message from a provider error body. Both Anthropic and OpenAI-compatible APIs
     * wrap the reason in {"error":{"message": "..."}}, so surface that instead of a 2000-char raw-JSON
     * dump. Some providers (e.g. OpenRouter, DeepSeek) return {"error": "plain string"} instead,
     * so we also check for a direct string value. Falls back to a whitespace-collapsed, bounded slice
     * of the raw body (e.g. an HTML 502 page).
     */
    fun humanError(raw: String): String {
        val message = runCatching {
            val root = parseObject(raw)
            val error = root.get("error")
            when {
                error == null || error.isJsonNull -> null
                error.isJsonObject -> error.asJsonObject.stringOrNull("message")
                error.isJsonPrimitive -> error.asString
                else -> null
            }
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: raw.replace(Regex("\\s+"), " ").trim().take(500)
    }
}

internal fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

internal fun JsonObject.intOr(key: String, default: Int): Int =
    get(key)?.takeIf { !it.isJsonNull }?.asInt ?: default

internal fun JsonObject.arrayOrEmpty(key: String): JsonArray =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

internal fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject
