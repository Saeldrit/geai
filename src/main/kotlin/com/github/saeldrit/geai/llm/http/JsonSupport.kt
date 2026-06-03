package com.github.saeldrit.geai.llm.http

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

/** Thin Gson wrapper. Gson ships inside the IDE, so no library is bundled by the plugin. */
internal object JsonSupport {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    // Parse leniently. The plugin compiles against Gson 2.10.1 but runs against whatever Gson the IDE
    // bundles; 2.11+ made JsonParser STRICT by default and throws MalformedJsonException on input the
    // old lenient parser accepted (e.g. a stray SSE chunk). setLenient(true) exists in both versions, so
    // reading through a lenient JsonReader stays robust regardless of the IDE's Gson.
    fun parseElement(raw: String): JsonElement =
        JsonParser.parseReader(JsonReader(StringReader(raw)).apply { isLenient = true })

    fun parseObject(raw: String): JsonObject = parseElement(raw).asJsonObject

    /**
     * A human-readable message from a provider error body. Both Anthropic and OpenAI-compatible APIs
     * wrap the reason in {"error":{"message": "..."}}, so surface that instead of a 2000-char raw-JSON
     * dump. Falls back to a whitespace-collapsed, bounded slice of the raw body (e.g. an HTML 502 page).
     */
    fun humanError(raw: String): String {
        val message = runCatching { parseObject(raw).objectOrNull("error")?.stringOrNull("message") }.getOrNull()
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
