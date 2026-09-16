package com.hhst.youtubelite.core

/** Escaping for synthetic manifests and the cast player page. */
object Markup {
    fun xml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    fun html(value: String): String = xml(value).replace("'", "&#39;")

    /**
     * Quoted JavaScript string literal, including the surrounding quotes.
     * `<` is escaped as `\u003C`: the only current call site embeds the
     * result inside a `<script>` block, and a raw `</script>` (or `<!--`)
     * inside the literal would terminate the element there. `/` after `<`
     * is covered by that escape.
     */
    fun jsString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '<' -> append("\\u003C")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(c)
            }
        }
        append('"')
    }
}
