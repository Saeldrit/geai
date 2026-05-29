package com.github.saeldrit.geai.llm.http

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Thin Gson wrapper. Gson ships inside the IDE, so no library is bundled by the plugin. */
internal object JsonSupport {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseObject(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    fun parseElement(raw: String): JsonElement = JsonParser.parseString(raw)
}

internal fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

internal fun JsonObject.intOr(key: String, default: Int): Int =
    get(key)?.takeIf { !it.isJsonNull }?.asInt ?: default

internal fun JsonObject.arrayOrEmpty(key: String): JsonArray =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

internal fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject
