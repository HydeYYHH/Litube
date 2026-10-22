package com.hhst.youtubelite.core

internal object BrowserUserAgent {
    fun from(default: String): String = default.replace("; wv", "")
        .replace(Regex("\\s+Version/[0-9.]+"), "")
        .let { if (it.contains("Chrome/") && !it.contains("Mobile"))
            it.replace(" Safari/", " Mobile Safari/") else it }
}
