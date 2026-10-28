package com.hhst.youtubelite.extractor

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Tolerant object read: a missing or non-object member reads as empty. */
internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject()

/** Strict string read: numbers and booleans are not coerced. */
internal fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

internal fun JsonObject.text(key: String, default: String): String = text(key) ?: default

/** Primitive read accepting strings, numbers and booleans; the session index arrives as either. */
internal fun JsonObject.raw(key: String): String? = (get(key) as? JsonPrimitive)?.asString

internal fun JsonObject.flag(key: String, default: Boolean): Boolean =
    (get(key) as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean ?: default

/** Fixture construction: strings, numbers, booleans, elements and nulls. */
internal fun jsonObjectOf(vararg members: Pair<String, Any?>): JsonObject {
    val result = JsonObject()
    for ((key, value) in members) when (value) {
        null -> result.add(key, JsonNull.INSTANCE)
        is String -> result.addProperty(key, value)
        is Boolean -> result.addProperty(key, value)
        is Number -> result.addProperty(key, value)
        is JsonElement -> result.add(key, value)
        else -> throw IllegalArgumentException("Unsupported member: ${value.javaClass}")
    }
    return result
}
