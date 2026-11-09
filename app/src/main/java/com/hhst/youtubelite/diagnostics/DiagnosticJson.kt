package com.hhst.youtubelite.diagnostics

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The diagnostics schema's single kotlinx.serialization entry point. Field maps
 * are JSON-shaped trees, so they ride a value serializer instead of reflection.
 * Defaults are always written, matching the historical record shape the archive
 * reader classifies.
 */
internal object DiagnosticJson {
    val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(record: DiagnosticEvent): String = json.encodeToString(DiagnosticEvent.serializer(), record)

    fun encodeMap(value: Map<String, Any?>): String =
        json.encodeToString(DiagnosticFieldsSerializer, value)

    fun decodeEvent(line: String): DiagnosticEvent = json.decodeFromString(DiagnosticEvent.serializer(), line)

    fun decodeEvent(element: JsonObject): DiagnosticEvent =
        json.decodeFromJsonElement(DiagnosticEvent.serializer(), element)

    fun decodeContext(raw: String): DiagnosticContext =
        json.decodeFromString(DiagnosticContext.serializer(), raw)

    fun encodeContext(value: DiagnosticContext): String =
        json.encodeToString(DiagnosticContext.serializer(), value)
}

/** One JSON-shaped value: the least common denominator of diagnostic field maps. */
internal object DiagnosticAnySerializer : KSerializer<Any?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Any?) {
        encoder.encodeSerializableValue(JsonElement.serializer(), value.toJsonElement())
    }

    override fun deserialize(decoder: Decoder): Any? =
        decoder.decodeSerializableValue(JsonElement.serializer()).fromElement()
}

internal object DiagnosticFieldsSerializer : KSerializer<Map<String, Any?>> {
    private val delegate = MapSerializer(String.serializer(), DiagnosticAnySerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, Any?>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Map<String, Any?> = delegate.deserialize(decoder)
}

/** Numbers mirror the historical LONG_OR_DOUBLE reading of archived rows. */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

internal fun JsonElement.fromElement(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonObject -> entries.associate { it.key to it.value.fromElement() }
    is JsonArray -> map { it.fromElement() }
}
