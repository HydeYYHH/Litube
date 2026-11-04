package com.hhst.youtubelite.core

import com.hhst.youtubelite.diagnostics.toJsonElement
import kotlinx.serialization.json.JsonElement

/** Test-side JSON encoding for instrumentation evidence payloads. */
internal object TestJson {
    fun any(value: Any?): String =
        com.hhst.youtubelite.diagnostics.DiagnosticJson.json
            .encodeToString(JsonElement.serializer(), value.toJsonElement())
}
