package com.hhst.youtubelite.extractor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Tolerant object read: a missing or non-object member reads as empty. */
internal fun JsonObject.obj(key: String): JsonObject = objOrNull(key) ?: JsonObject(emptyMap())

/** Tolerant member read: a missing or non-object member reads as null. */
internal fun JsonObject.objOrNull(key: String): JsonObject? = get(key) as? JsonObject

/** Strict string read: numbers and booleans are not coerced. */
internal fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.text(key: String, default: String): String = text(key) ?: default

/** Primitive read accepting strings, numbers and booleans; the session index arrives as either. */
internal fun JsonObject.raw(key: String): String? = get(key).asText

internal fun JsonObject.flag(key: String, default: Boolean): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: default

/** Wrong-shaped and null members stay absent. */
internal val JsonElement?.asObj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.asText: String?
    get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
internal val JsonElement?.asBool: Boolean?
    get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.booleanOrNull
internal val JsonElement?.asLongNum: Long?
    get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let { it.longOrNull ?: it.content.toLongOrNull() }

internal fun jsonObjectOf(vararg members: Pair<String, Any?>): JsonObject {
    val result = LinkedHashMap<String, JsonElement>()
    for ((key, value) in members) result[key] = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> throw IllegalArgumentException("Unsupported member: ${value.javaClass}")
    }
    return JsonObject(result)
}

internal fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)
