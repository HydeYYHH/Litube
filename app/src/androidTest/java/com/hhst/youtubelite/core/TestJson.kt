package com.hhst.youtubelite.core

import com.hhst.youtubelite.diagnostics.toJsonElement
import kotlinx.serialization.json.JsonElement

internal object TestJson {
    fun any(value: Any?): String =
        com.hhst.youtubelite.diagnostics.DiagnosticJson.json
            .encodeToString(JsonElement.serializer(), value.toJsonElement())
}
